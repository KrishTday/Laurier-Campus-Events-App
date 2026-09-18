package com.laurier.events;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;

/**
 * Laurier Campus Events App backend.
 *
 * Pure JDK HTTP server (no framework) + SQLite via JDBC, so the whole
 * backend runs from `java` with only the sqlite-jdbc jar on the classpath.
 * Serves the REST API under /api/* and the built frontend (backend/web)
 * for everything else, so the app ships as a single process.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        String dbPath = System.getenv().getOrDefault("DB_PATH", "data/events.db");
        Path webRoot = Paths.get(System.getenv().getOrDefault("WEB_ROOT", "web"));

        Db db = new Db(dbPath);
        EventRepository events = new EventRepository(db);
        StudentRepository students = new StudentRepository(db);

        EmbeddingClient embeddingClient = new EmbeddingClient(System.getenv("VOYAGE_API_KEY"));
        ClaudeClient claudeClient = new ClaudeClient(System.getenv("ANTHROPIC_API_KEY"));
        ChatService chat = new ChatService(db, events, embeddingClient, claudeClient);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/api/events", ex -> handle(ex, () -> handleEvents(ex, events, students)));
        server.createContext("/api/students", ex -> handle(ex, () -> handleStudents(ex, events, students)));
        server.createContext("/api/chat", ex -> handle(ex, () -> handleChat(ex, chat)));
        server.createContext("/", ex -> handle(ex, () -> serveStatic(ex, webRoot)));

        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Laurier Campus Events App listening on http://localhost:" + port);
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void handle(HttpExchange ex, Action action) {
        try {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                addCors(ex);
                ex.sendResponseHeaders(204, -1);
                return;
            }
            addCors(ex);
            action.run();
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, Map.of("error", e.getMessage()));
        } catch (NoSuchElementException e) {
            sendJson(ex, 404, Map.of("error", "Not found"));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, Map.of("error", "Internal server error"));
        } finally {
            ex.close();
        }
    }

    private static void addCors(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, X-Student-Key");
    }

    // ---------- /api/events ----------

    private static void handleEvents(HttpExchange ex, EventRepository events, StudentRepository students) throws Exception {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        String[] parts = path.replaceFirst("^/api/events/?", "").split("/");
        Long studentId = resolveStudent(ex, students, false);

        if (path.equals("/api/events") || path.equals("/api/events/")) {
            if (method.equals("GET")) {
                Map<String, String> q = queryParams(ex);
                sendJson(ex, 200, events.findAll(q.get("category"), q.get("search"), studentId));
                return;
            }
            if (method.equals("POST")) {
                Map<String, Object> body = Json.readObject(readBody(ex));
                long id = events.create(body);
                sendJson(ex, 201, events.findById(id, studentId).orElseThrow());
                return;
            }
        } else if (parts.length == 1 && !parts[0].isBlank()) {
            long id = Long.parseLong(parts[0]);
            if (method.equals("GET")) {
                sendJson(ex, 200, events.findById(id, studentId).orElseThrow());
                return;
            }
            if (method.equals("PUT")) {
                Map<String, Object> body = Json.readObject(readBody(ex));
                if (!events.update(id, body)) throw new NoSuchElementException();
                sendJson(ex, 200, events.findById(id, studentId).orElseThrow());
                return;
            }
            if (method.equals("DELETE")) {
                if (!events.delete(id)) throw new NoSuchElementException();
                sendJson(ex, 200, Map.of("deleted", true));
                return;
            }
        }
        sendJson(ex, 405, Map.of("error", "Method not allowed"));
    }

    // ---------- /api/chat ----------

    private static void handleChat(HttpExchange ex, ChatService chat) throws Exception {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "Method not allowed"));
            return;
        }
        Map<String, Object> body = Json.readObject(readBody(ex));
        String message = body.get("message") == null ? null : String.valueOf(body.get("message"));
        try {
            sendJson(ex, 200, chat.answer(message));
        } catch (IllegalStateException e) {
            sendJson(ex, 503, Map.of("error", e.getMessage()));
        }
    }

    // ---------- /api/students/{key}/tracked[/{eventId}] ----------

    private static void handleStudents(HttpExchange ex, EventRepository events, StudentRepository students) throws Exception {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        String[] parts = path.replaceFirst("^/api/students/?", "").split("/");
        // parts: [key, "tracked", (eventId)?]
        if (parts.length < 2 || !parts[1].equals("tracked")) {
            sendJson(ex, 404, Map.of("error", "Not found"));
            return;
        }
        String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
        if (key.isBlank()) {
            sendJson(ex, 400, Map.of("error", "Missing student key"));
            return;
        }
        long studentId = students.getOrCreate(key, null);

        if (parts.length == 2) {
            if (method.equals("GET")) {
                sendJson(ex, 200, Map.of("trackedEventIds", events.trackedEventIds(studentId)));
                return;
            }
        } else if (parts.length == 3) {
            long eventId = Long.parseLong(parts[2]);
            if (method.equals("POST")) {
                events.track(studentId, eventId);
                sendJson(ex, 200, Map.of("tracked", true));
                return;
            }
            if (method.equals("DELETE")) {
                events.untrack(studentId, eventId);
                sendJson(ex, 200, Map.of("tracked", false));
                return;
            }
        }
        sendJson(ex, 405, Map.of("error", "Method not allowed"));
    }

    private static Long resolveStudent(HttpExchange ex, StudentRepository students, boolean create) throws SQLException {
        List<String> header = ex.getRequestHeaders().get("X-Student-Key");
        String key = (header == null || header.isEmpty()) ? null : header.get(0);
        if (key == null || key.isBlank()) return null;
        if (create) {
            return students.getOrCreate(key, null);
        }
        return students.find(key);
    }

    // ---------- static file serving ----------

    private static void serveStatic(HttpExchange ex, Path webRoot) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "Method not allowed"));
            return;
        }
        String reqPath = ex.getRequestURI().getPath();
        if (reqPath.equals("/")) reqPath = "/index.html";
        Path file = webRoot.resolve("." + reqPath).normalize();
        if (!file.startsWith(webRoot) || !Files.exists(file) || Files.isDirectory(file)) {
            file = webRoot.resolve("index.html"); // SPA fallback
        }
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().add("Content-Type", contentType(file.toString()));
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    // ---------- helpers ----------

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(HttpExchange ex, int status, Object payload) {
        try {
            byte[] bytes = Json.write(payload).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, String> queryParams(HttpExchange ex) {
        String query = ex.getRequestURI().getRawQuery();
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            map.put(k, v);
        }
        return map;
    }
}
