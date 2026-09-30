package com.smartstudy.repository;

import com.smartstudy.model.Quiz;
import com.smartstudy.model.Topic;
import com.smartstudy.util.DuplicateResourceException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class JdbcQuizRepository implements QuizRepository {

    private static final int MYSQL_DUPLICATE_ENTRY = 1062;

    private static final String SELECT_ALL =
            "SELECT id, title, description, created_at FROM quizzes ORDER BY id";
    private static final String SELECT_BY_ID =
            "SELECT id, title, description, created_at FROM quizzes WHERE id = ?";
    private static final String INSERT =
            "INSERT INTO quizzes (title, description) VALUES (?, ?)";
    // DISTINCT: a quiz can have several questions on the same topic, but each topic should be
    // listed once per quiz. Ordering by topic name gives deterministic per-quiz topic lists.
    private static final String SELECT_TOPICS_FOR_QUIZZES_PREFIX =
            "SELECT DISTINCT q.quiz_id AS quiz_id, t.id AS topic_id, t.name AS topic_name "
                    + "FROM questions q JOIN topics t ON t.id = q.topic_id WHERE q.quiz_id IN (";
    private static final String SELECT_TOPICS_FOR_QUIZZES_SUFFIX = ") ORDER BY q.quiz_id, t.name";

    private final Database database;

    public JdbcQuizRepository(Database database) {
        this.database = database;
    }

    @Override
    public List<Quiz> findAll() {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_ALL);
             ResultSet rs = ps.executeQuery()) {
            List<Quiz> quizzes = new ArrayList<>();
            while (rs.next()) {
                quizzes.add(map(rs));
            }
            return quizzes;
        } catch (SQLException e) {
            throw new DataAccessException("Failed to query quizzes", e);
        }
    }

    @Override
    public Optional<Quiz> findById(long id) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_BY_ID)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to query quizzes", e);
        }
    }

    @Override
    public Quiz create(String title, String description) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, title);
            if (description == null) {
                ps.setNull(2, java.sql.Types.VARCHAR);
            } else {
                ps.setString(2, description);
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return findById(keys.getLong(1)).orElseThrow();
            }
        } catch (SQLException e) {
            if (e.getErrorCode() == MYSQL_DUPLICATE_ENTRY) {
                throw new DuplicateResourceException("A quiz with this title already exists");
            }
            throw new DataAccessException("Failed to create quiz", e);
        }
    }

    @Override
    public Map<Long, List<Topic>> topicsByQuizIds(Collection<Long> quizIds) {
        if (quizIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = quizIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        String sql = SELECT_TOPICS_FOR_QUIZZES_PREFIX + placeholders + SELECT_TOPICS_FOR_QUIZZES_SUFFIX;
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (Long id : quizIds) {
                ps.setLong(i++, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                Map<Long, List<Topic>> result = new LinkedHashMap<>();
                while (rs.next()) {
                    long quizId = rs.getLong("quiz_id");
                    Topic topic = new Topic(rs.getLong("topic_id"), rs.getString("topic_name"));
                    result.computeIfAbsent(quizId, k -> new ArrayList<>()).add(topic);
                }
                return result;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to query quiz topics", e);
        }
    }

    private static Quiz map(ResultSet rs) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new Quiz(rs.getLong("id"), rs.getString("title"), rs.getString("description"),
                createdAt == null ? null : createdAt.toInstant());
    }
}
