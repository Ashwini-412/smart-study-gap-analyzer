package com.smartstudy.support;

import com.smartstudy.config.AppConfig;
import com.smartstudy.repository.Database;
import org.junit.jupiter.api.Assumptions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Real-MySQL tests run only when DB_PASSWORD is set (otherwise they are reported as
 * skipped, never as passed). They use unique throw-away emails and delete what they create.
 */
public final class DbTestSupport {

    private DbTestSupport() {
    }

    public static Database databaseOrSkip() {
        String pw = System.getenv("DB_PASSWORD");
        Assumptions.assumeTrue(pw != null && !pw.isBlank(), "DB_PASSWORD not set - skipping MySQL test");
        return new Database(AppConfig.load());
    }

    /** Deleting the student cascades to its auth_sessions rows. */
    public static void deleteStudentByEmail(Database db, String email) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM students WHERE email = ?")) {
            ps.setString(1, email);
            ps.executeUpdate();
        }
    }

    public static void deleteTopicById(Database db, long id) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM topics WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /** Deleting the quiz cascades to its questions, which cascades to their options. */
    public static void deleteQuizById(Database db, long id) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM quizzes WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }
}
