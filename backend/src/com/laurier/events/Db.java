package com.laurier.events;

import java.io.File;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

/**
 * Owns the single SQLite connection. SQLite is a single-writer database and
 * this app expects light, local traffic, so all repository calls synchronize
 * on this connection rather than pooling.
 */
public final class Db {

    public final Connection conn;

    public Db(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
            new File(path).getParentFile().mkdirs();
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
            }
            createSchema();
            seedIfEmpty();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize database", e);
        }
    }

    private void createSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    category TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    location TEXT NOT NULL DEFAULT '',
                    organizer TEXT NOT NULL DEFAULT '',
                    event_start TEXT NOT NULL,
                    event_end TEXT,
                    rsvp_deadline TEXT,
                    created_at TEXT NOT NULL
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS students (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    student_key TEXT NOT NULL UNIQUE,
                    display_name TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS tracked_events (
                    student_id INTEGER NOT NULL REFERENCES students(id) ON DELETE CASCADE,
                    event_id INTEGER NOT NULL REFERENCES events(id) ON DELETE CASCADE,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY (student_id, event_id)
                )
            """);
        }
    }

    private void seedIfEmpty() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM events")) {
            rs.next();
            if (rs.getInt(1) > 0) return;
        }

        ZoneId zone = ZoneId.of("America/Toronto");
        LocalDate today = LocalDate.now(zone);
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

        Object[][] seed = {
            // title, category, description, location, organizer, daysFromNow start, start time, end time, rsvp days offset (or null)
            {"Fall Welcome Week Kickoff", "Orientation",
             "Opening ceremony for new and returning Golden Hawks, with music, campus tours, and a welcome address from the Students' Union.",
             "Concourse, Student Union Building", "Wilfrid Laurier Students' Union", 1, "11:00", "14:00", null},
            {"Golden Hawks Career Fair", "Career",
             "Meet recruiters from over 60 employers across tech, finance, and public service. Bring copies of your resume.",
             "Athletic Complex, Main Gym", "Career Centre", 3, "10:00", "15:00", 2},
            {"Club Fair: Meet Your Clubs", "Clubs",
             "Browse tables from more than 100 student clubs and sign up on the spot. Free pizza while supplies last.",
             "The Quad", "Clubs & Societies Office", 4, "12:00", "16:00", null},
            {"Intro to Financial Aid Workshop", "Academic",
             "A walkthrough of OSAP, scholarships, and bursaries for the upcoming academic year, run by the Financial Aid office.",
             "Room 1E1, Bricker Academic Building", "Financial Aid Office", 6, "13:00", "14:30", 5},
            {"Golden Hawks Football Home Opener", "Sports",
             "Cheer on the Golden Hawks in their first home game of the season. Student tickets are free with a valid ID.",
             "University Stadium", "Laurier Athletics", 8, "19:00", "22:00", null},
            {"Midterm Study Jam", "Academic",
             "Drop-in group study session with peer tutors on hand for stats, econ, and intro programming courses.",
             "Library Learning Commons", "Student Success Centre", 10, "17:00", "21:00", null},
            {"Battle of the Bands", "Social",
             "Student bands compete for a slot opening the winter concert. Doors open half an hour before showtime.",
             "Wilf's", "Laurier Musicians' Collective", 12, "20:00", "23:00", 11},
            {"Co-op Application Deadline Info Session", "Career",
             "Everything you need to know before the winter co-op application window closes, including resume review sign-ups.",
             "Room 202, Schlegel Building", "Co-operative Education", 14, "12:00", "13:00", 15},
            {"International Culture Night", "Social",
             "An evening of food, performances, and stories from Laurier's international student community. All welcome.",
             "Senate and Board Chamber", "International Student Association", 17, "18:00", "21:00", 16},
            {"Winter Term Course Registration Opens", "Academic",
             "Registration windows open by year of study. Check your appointment time on LORIS before this date.",
             "Online (LORIS)", "Office of the Registrar", 20, "07:00", "08:00", null},
        };

        String sql = """
            INSERT INTO events (title, category, description, location, organizer, event_start, event_end, rsvp_deadline, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            String nowIso = ZonedDateTime.now(zone).format(DateTimeFormatter.ISO_INSTANT);
            for (Object[] row : seed) {
                LocalDate startDate = today.plusDays((Integer) row[5]);
                LocalDateTime start = LocalDateTime.parse(startDate + "T" + row[6] + ":00");
                LocalDateTime end = LocalDateTime.parse(startDate + "T" + row[7] + ":00");
                ps.setString(1, (String) row[0]);
                ps.setString(2, (String) row[1]);
                ps.setString(3, (String) row[2]);
                ps.setString(4, (String) row[3]);
                ps.setString(5, (String) row[4]);
                ps.setString(6, start.format(fmt));
                ps.setString(7, end.format(fmt));
                if (row[8] != null) {
                    LocalDateTime deadline = startDate.minusDays((Integer) (row[5]) - (Integer) row[8] >= 0 ? 0 : 0).atTime(23, 59);
                    // rsvp offset stored as an absolute day count from today, not relative to start
                    LocalDate deadlineDate = today.plusDays((Integer) row[8]);
                    ps.setString(8, deadlineDate.atTime(23, 59).format(fmt));
                } else {
                    ps.setNull(8, Types.VARCHAR);
                }
                ps.setString(9, nowIso);
                ps.executeUpdate();
            }
        }
    }
}
