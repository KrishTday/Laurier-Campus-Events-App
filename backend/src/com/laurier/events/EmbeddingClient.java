package com.laurier.events;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin client for Voyage AI's embeddings endpoint (the "retrieval" side of
 * the RAG pipeline behind /api/chat). Turns event text and student questions
 * into vectors so ChatService can rank events by semantic similarity instead
 * of plain keyword matching.
 *
 * Uses only java.net.http.HttpClient (built into the JDK since 11), so this
 * doesn't add a dependency beyond the existing SQLite driver jar.
 */
public final class EmbeddingClient {

    private static final String ENDPOINT = "https://api.voyageai.com/v1/embeddings";
    private static final String MODEL = "voyage-3.5-lite";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String apiKey;

    public EmbeddingClient(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * @param inputType "query" when embedding a student's question, "document"
     *                  when embedding stored event text. Voyage optimizes the
     *                  vectors differently for each side of a retrieval task.
     */
    public List<double[]> embed(List<String> texts, String inputType) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", texts);
        body.put("model", MODEL);
        body.put("input_type", inputType);

        HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new RuntimeException("Voyage AI embeddings request failed: " + res.statusCode() + " " + res.body());
        }

        Map<String, Object> parsed = Json.readObject(res.body());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) parsed.get("data");

        List<double[]> out = new ArrayList<>(Collections.nCopies(data.size(), null));
        for (Map<String, Object> item : data) {
            int index = ((Number) item.get("index")).intValue();
            @SuppressWarnings("unchecked")
            List<Object> raw = (List<Object>) item.get("embedding");
            double[] vec = new double[raw.size()];
            for (int i = 0; i < raw.size(); i++) vec[i] = ((Number) raw.get(i)).doubleValue();
            out.set(index, vec);
        }
        return out;
    }
}
