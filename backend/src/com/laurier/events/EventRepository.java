package com.laurier.events;

import java.sql.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * All reads/writes for the events table, plus the per-student tracked_events
 * join needed to compute a "trackedByMe" / "trackedCount" flag on each row.
 */
public final class EventRepository {

    private final Db db;

    public EventRepository(Db db) {
        this.db = db;
    }

    public synchronized List<Map<String, Object>> findAll(String category, String search, Long studentId) throws SQLException {
        StringBuilder sql = new StringBuilder("""
            SELECT e.*,
                   (SELECT COUNT(*) FROM tracked_events t WHERE t.event_id = e.id) AS tracked_count
            FROM events e
            WHERE 1=1
        """);
        List<Object> params = new ArrayList<>();
        if (category != null && !category.isBlank() && !category.equalsIgnoreCase("All")) {
            sql.append(" AND e.category = ?");
            params.add(category);
        }
        if (search != null && !search.isBlank()) {
            sql.append(" AND (LOWER(e.title) LIKE ? OR LOWER(e.description) LIKE ? OR LOWER(e.location) LIKE ?)");
            String like = "%" + search.toLowerCase() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        sql.append(" ORDER BY e.event_start ASC");

        try (PreparedStatement ps = db.conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                Set<Long> trackedIds = studentId == null ? Set.of() : trackedEventIds(studentId);
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rowToMap(rs, trackedIds));
                }
                return out;
            }
        }
    }

    public synchronized Optional<Map<String, Object>> findById(long id, Long studentId) throws SQLException {
        String sql = """
            SELECT e.*,
                   (SELECT COUNT(*) FROM tracked_events t WHERE t.event_id = e.id) AS tracked_count
            FROM events e WHERE e.id = ?
        """;
        try (PreparedStatement ps = db.conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                Set<Long> trackedIds = studentId == null ? Set.of() : trackedEventIds(studentId);
                return Optional.of(rowToMap(rs, trackedIds));
            }
        }
    }

    public synchronized long create(Map<String, Object> body) throws SQLException {
        String sql = """
            INSERT INTO events (title, category, description, location, organizer, event_start, event_end, rsvp_deadline, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = db.conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, requireString(body, "title"));
            ps.setString(2, requireString(body, "category"));
            ps.setString(3, str(body.get("description"), ""));
            ps.setString(4, str(body.get("location"), ""));
            ps.setString(5, str(body.get("organizer"), ""));
            ps.setString(6, requireString(body, "eventStart"));
            ps.setString(7, str(body.get("eventEnd"), null));
            ps.setString(8, str(body.get("rsvpDeadline"), null));
            ps.setString(9, ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public synchronized boolean update(long id, Map<String, Object> body) throws SQLException {
        String sql = """
            UPDATE events SET title=?, category=?, description=?, location=?, organizer=?,
                event_start=?, event_end=?, rsvp_deadline=? WHERE id=?
        """;
        try (PreparedStatement ps = db.conn.prepareStatement(sql)) {
            ps.setString(1, requireString(body, "title"));
            ps.setString(2, requireString(body, "category"));
            ps.setString(3, str(body.get("description"), ""));
            ps.setString(4, str(body.get("location"), ""));
            ps.setString(5, str(body.get("organizer"), ""));
            ps.setString(6, requireString(body, "eventStart"));
            ps.setString(7, str(body.get("eventEnd"), null));
            ps.setString(8, str(body.get("rsvpDeadline"), null));
            ps.setLong(9, id);
            return ps.executeUpdate() > 0;
        }
    }

    public synchronized boolean delete(long id) throws SQLException {
        try (PreparedStatement ps = db.conn.prepareStatement("DELETE FROM events WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    // ---------- Tracking ----------

    public synchronized Set<Long> trackedEventIds(long studentId) throws SQLException {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT event_id FROM tracked_events WHERE student_id = ?")) {
            ps.setLong(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                Set<Long> ids = new HashSet<>();
                while (rs.next()) ids.add(rs.getLong(1));
                return ids;
            }
        }
    }

    public synchronized void track(long studentId, long eventId) throws SQLException {
        try (PreparedStatement ps = db.conn.prepareStatement("""
                INSERT OR IGNORE INTO tracked_events (student_id, event_id, created_at) VALUES (?, ?, ?)
                """)) {
            ps.setLong(1, studentId);
            ps.setLong(2, eventId);
            ps.setString(3, ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT));
            ps.executeUpdate();
        }
    }

    public synchronized void untrack(long studentId, long eventId) throws SQLException {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "DELETE FROM tracked_events WHERE student_id = ? AND event_id = ?")) {
            ps.setLong(1, studentId);
            ps.setLong(2, eventId);
            ps.executeUpdate();
        }
    }

    // ---------- helpers ----------

    private static Map<String, Object> rowToMap(ResultSet rs, Set<Long> trackedIds) throws SQLException {
        long id = rs.getLong("id");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", rs.getString("title"));
        m.put("category", rs.getString("category"));
        m.put("description", rs.getString("description"));
        m.put("location", rs.getString("location"));
        m.put("organizer", rs.getString("organizer"));
        m.put("eventStart", rs.getString("event_start"));
        m.put("eventEnd", rs.getString("event_end"));
        m.put("rsvpDeadline", rs.getString("rsvp_deadline"));
        m.put("createdAt", rs.getString("created_at"));
        m.put("trackedCount", rs.getInt("tracked_count"));
        m.put("trackedByMe", trackedIds.contains(id));
        return m;
    }

    private static String requireString(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        return String.valueOf(v);
    }

    private static String str(Object v, String def) {
        return v == null ? def : String.valueOf(v);
    }
}
