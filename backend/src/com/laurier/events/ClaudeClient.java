package com.laurier.events;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin client for the Anthropic Messages API (the "generation" side of the
 * RAG pipeline behind /api/chat). Takes a student's question plus the
 * events ChatService retrieved and asks Claude to answer using only that
 * context, so answers stay grounded in real events instead of the model
 * guessing.
 */
public final class ClaudeClient {

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-haiku-4-5-20251001";
    private static final String API_VERSION = "2023-06-01";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String apiKey;

    public ClaudeClient(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String ask(String question, String eventContext) throws Exception {
        String system = "You are the event assistant for the Laurier Campus Events App. "
                + "Answer the student's question using ONLY the events listed below. "
                + "Keep answers short (2-4 sentences), mention specific dates, locations, "
                + "or RSVP deadlines when relevant, and if none of the events answer the "
                + "question, say so plainly instead of guessing.\n\nEvents:\n" + eventContext;

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", question);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MODEL);
        body.put("max_tokens", 400);
        body.put("system", system);
        body.put("messages", List.of(message));

        HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new RuntimeException("Claude API request failed: " + res.statusCode() + " " + res.body());
        }

        Map<String, Object> parsed = Json.readObject(res.body());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) parsed.get("content");
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> block : content) {
            if ("text".equals(block.get("type"))) {
                sb.append((String) block.get("text"));
            }
        }
        return sb.toString().trim();
    }
}
