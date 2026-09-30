package com.smartstudy.repository;

import com.smartstudy.model.AuthSession;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

public class JdbcSessionRepository implements SessionRepository {

    private static final String INSERT =
            "INSERT INTO auth_sessions (student_id, token_hash, expires_at) VALUES (?, ?, ?)";
    private static final String SELECT_BY_HASH =
            "SELECT id, student_id, expires_at FROM auth_sessions WHERE token_hash = ?";
    private static final String DELETE_BY_ID = "DELETE FROM auth_sessions WHERE id = ?";

    private final Database database;

    public JdbcSessionRepository(Database database) {
        this.database = database;
    }

    @Override
    public void create(long studentId, String tokenHash, Instant expiresAt) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setLong(1, studentId);
            ps.setString(2, tokenHash);
            ps.setTimestamp(3, Timestamp.from(expiresAt));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to create session", e);
        }
    }

    @Override
    public Optional<AuthSession> findByTokenHash(String tokenHash) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_BY_HASH)) {
            ps.setString(1, tokenHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AuthSession(rs.getLong("id"), rs.getLong("student_id"),
                        rs.getTimestamp("expires_at").toInstant()));
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to query sessions", e);
        }
    }

    @Override
    public void deleteById(long sessionId) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(DELETE_BY_ID)) {
            ps.setLong(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Failed to delete session", e);
        }
    }
}
