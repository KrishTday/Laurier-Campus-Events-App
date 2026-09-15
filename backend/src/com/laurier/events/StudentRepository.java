package com.laurier.events;

import java.sql.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Students aren't authenticated in this build; the frontend generates a
 * random key and stores it in localStorage so "tracked events" persist
 * across visits from the same browser. This repo just maps that key to a
 * row id for the tracked_events foreign key.
 */
public final class StudentRepository {

    private final Db db;

    public StudentRepository(Db db) {
        this.db = db;
    }

    public synchronized long getOrCreate(String studentKey, String displayName) throws SQLException {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT id FROM students WHERE student_key = ?")) {
            ps.setString(1, studentKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO students (student_key, display_name, created_at) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, studentKey);
            ps.setString(2, displayName == null || displayName.isBlank() ? "Student" : displayName);
            ps.setString(3, ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public synchronized Long find(String studentKey) throws SQLException {
        if (studentKey == null || studentKey.isBlank()) return null;
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT id FROM students WHERE student_key = ?")) {
            ps.setString(1, studentKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }
}
