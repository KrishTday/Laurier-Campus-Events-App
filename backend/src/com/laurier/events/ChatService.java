package com.laurier.events;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates the RAG pipeline behind /api/chat:
 *
 *   1. Embed every event's text via Voyage AI, caching each vector in SQLite
 *      keyed by a hash of the event's text so it's only recomputed when the
 *      event actually changes (see event_embeddings in Db.java).
 *   2. Embed the student's question and rank events by cosine similarity.
 *   3. Hand the top matches to Claude as grounding context and return its
 *      answer plus which events it was based on, so the frontend can surface
 *      them as clickable chips.
 */
public final class ChatService {

    private static final int TOP_K = 5;

    private final Db db;
    private final EventRepository events;
    private final EmbeddingClient embeddingClient;
    private final ClaudeClient claudeClient;

    public ChatService(Db db, EventRepository events, EmbeddingClient embeddingClient, ClaudeClient claudeClient) {
        this.db = db;
        this.events = events;
        this.embeddingClient = embeddingClient;
        this.claudeClient = claudeClient;
    }

    public boolean isConfigured() {
        return embeddingClient.isConfigured() && claudeClient.isConfigured();
    }

    public Map<String, Object> answer(String question) throws Exception {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Missing 'message'");
        }
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "Chat isn't configured yet. Set VOYAGE_API_KEY and ANTHROPIC_API_KEY on the server.");
        }

        List<Map<String, Object>> all = events.findAll(null, null, null);
        if (all.isEmpty()) {
            return Map.of("answer", "There aren't any events in the system yet.", "events", List.of());
        }

        Map<Long, double[]> vectors = ensureEmbeddings(all);
        double[] queryVector = embeddingClient.embed(List.of(question), "query").get(0);

        Map<Long, Double> scores = new HashMap<>();
        for (Map<String, Object> e : all) {
            long id = idOf(e);
            scores.put(id, cosineSimilarity(queryVector, vectors.get(id)));
        }

        List<Map<String, Object>> ranked = new ArrayList<>(all);
        ranked.sort((a, b) -> Double.compare(scores.get(idOf(b)), scores.get(idOf(a))));
        List<Map<String, Object>> top = ranked.subList(0, Math.min(TOP_K, ranked.size()));

        String answerText = claudeClient.ask(question, buildContext(top));

        List<Map<String, Object>> sources = new ArrayList<>();
        for (Map<String, Object> e : top) {
            sources.add(Map.of("id", e.get("id"), "title", e.get("title")));
        }
        return Map.of("answer", answerText, "events", sources);
    }

    // ---------- embedding cache ----------

    private Map<Long, double[]> ensureEmbeddings(List<Map<String, Object>> all) throws Exception {
        Map<Long, double[]> vectors = new HashMap<>();
        Map<Long, String> hashes = new HashMap<>();
        List<Map<String, Object>> missing = new ArrayList<>();

        for (Map<String, Object> e : all) {
            long id = idOf(e);
            String hash = sha256(eventText(e));
            hashes.put(id, hash);
            Optional<double[]> cached = getCached(id, hash);
            if (cached.isPresent()) {
                vectors.put(id, cached.get());
            } else {
                missing.add(e);
            }
        }

        if (!missing.isEmpty()) {
            List<String> texts = missing.stream().map(ChatService::eventText).toList();
            List<double[]> embedded = embeddingClient.embed(texts, "document");
            for (int i = 0; i < missing.size(); i++) {
                long id = idOf(missing.get(i));
                double[] vec = embedded.get(i);
                vectors.put(id, vec);
                storeCached(id, hashes.get(id), vec);
            }
        }
        return vectors;
    }

    private synchronized Optional<double[]> getCached(long eventId, String hash) throws SQLException {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT embedding FROM event_embeddings WHERE event_id = ? AND text_hash = ?")) {
            ps.setLong(1, eventId);
            ps.setString(2, hash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(parseVector(rs.getString(1)));
            }
        }
    }

    private synchronized void storeCached(long eventId, String hash, double[] vector) throws SQLException {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT OR REPLACE INTO event_embeddings (event_id, text_hash, embedding) VALUES (?, ?, ?)")) {
            ps.setLong(1, eventId);
            ps.setString(2, hash);
            ps.setString(3, writeVector(vector));
            ps.executeUpdate();
        }
    }

    // ---------- helpers ----------

    private static long idOf(Map<String, Object> e) {
        return ((Number) e.get("id")).longValue();
    }

    private static String eventText(Map<String, Object> e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.get("title")).append(". ").append(e.get("category")).append(". ");
        sb.append(e.get("description")).append(" Location: ").append(e.get("location"));
        sb.append(". Starts: ").append(e.get("eventStart"));
        if (e.get("rsvpDeadline") != null) {
            sb.append(". RSVP by: ").append(e.get("rsvpDeadline"));
        }
        return sb.toString();
    }

    private static String buildContext(List<Map<String, Object>> top) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> e : top) {
            sb.append("- [id ").append(e.get("id")).append("] ").append(eventText(e)).append('\n');
        }
        return sb.toString();
    }

    private static double cosineSimilarity(double[] a, double[] b) {
        if (a == null || b == null) return -1.0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static String sha256(String text) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static double[] parseVector(String s) {
        String[] parts = s.split(",");
        double[] v = new double[parts.length];
        for (int i = 0; i < parts.length; i++) v[i] = Double.parseDouble(parts[i]);
        return v;
    }

    private static String writeVector(double[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10);
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.toString();
    }
}
