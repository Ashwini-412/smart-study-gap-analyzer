package com.smartstudy.repository;

import com.smartstudy.model.Question;
import com.smartstudy.model.QuestionOption;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class JdbcQuestionRepository implements QuestionRepository {

    private static final String SELECT_BY_QUIZ =
            "SELECT id, quiz_id, topic_id, question_text, position FROM questions "
                    + "WHERE quiz_id = ? ORDER BY position";
    private static final String SELECT_OPTIONS_PREFIX =
            "SELECT id, question_id, option_text, is_correct FROM question_options WHERE question_id IN (";
    private static final String SELECT_OPTIONS_SUFFIX = ") ORDER BY question_id, id";
    // FOR UPDATE serialises concurrent question creation for the same quiz, so two requests
    // arriving together cannot compute the same next position.
    private static final String NEXT_POSITION =
            "SELECT COALESCE(MAX(position), 0) + 1 FROM questions WHERE quiz_id = ? FOR UPDATE";
    private static final String INSERT_QUESTION =
            "INSERT INTO questions (quiz_id, topic_id, question_text, position) VALUES (?, ?, ?, ?)";
    private static final String INSERT_OPTION =
            "INSERT INTO question_options (question_id, option_text, is_correct) VALUES (?, ?, ?)";

    private final Database database;

    public JdbcQuestionRepository(Database database) {
        this.database = database;
    }

    @Override
    public List<Question> findByQuizId(long quizId) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_BY_QUIZ)) {
            ps.setLong(1, quizId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Question> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapQuestion(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to query questions", e);
        }
    }

    @Override
    public Map<Long, List<QuestionOption>> optionsByQuestionIds(Collection<Long> questionIds) {
        if (questionIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = questionIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        String sql = SELECT_OPTIONS_PREFIX + placeholders + SELECT_OPTIONS_SUFFIX;
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (Long id : questionIds) {
                ps.setLong(i++, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                Map<Long, List<QuestionOption>> result = new LinkedHashMap<>();
                while (rs.next()) {
                    long questionId = rs.getLong("question_id");
                    result.computeIfAbsent(questionId, k -> new ArrayList<>()).add(mapOption(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to query question options", e);
        }
    }

    @Override
    public Created createWithOptions(long quizId, long topicId, String questionText, List<NewOption> options) {
        try (Connection c = database.getConnection()) {
            try {
                c.setAutoCommit(false);
                int position = nextPosition(c, quizId);
                long questionId = insertQuestion(c, quizId, topicId, questionText, position);
                List<QuestionOption> saved = insertOptions(c, questionId, options);
                c.commit();
                return new Created(new Question(questionId, quizId, topicId, questionText, position), saved);
            } catch (SQLException e) {
                rollbackQuietly(c);
                throw new DataAccessException("Failed to create question", e);
            } catch (RuntimeException e) {
                rollbackQuietly(c);
                throw e;
            } finally {
                resetAutoCommitQuietly(c);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to create question", e);
        }
    }

    private int nextPosition(Connection c, long quizId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(NEXT_POSITION)) {
            ps.setLong(1, quizId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private long insertQuestion(Connection c, long quizId, long topicId, String text, int position)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(INSERT_QUESTION, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, quizId);
            ps.setLong(2, topicId);
            ps.setString(3, text);
            ps.setInt(4, position);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private List<QuestionOption> insertOptions(Connection c, long questionId, List<NewOption> options)
            throws SQLException {
        List<QuestionOption> saved = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(INSERT_OPTION, Statement.RETURN_GENERATED_KEYS)) {
            for (NewOption option : options) {
                ps.setLong(1, questionId);
                ps.setString(2, option.text());
                ps.setBoolean(3, option.correct());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    saved.add(new QuestionOption(keys.getLong(1), questionId, option.text(), option.correct()));
                }
            }
        }
        return saved;
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // Best effort: the connection is about to be closed either way.
        }
    }

    private static void resetAutoCommitQuietly(Connection c) {
        try {
            c.setAutoCommit(true);
        } catch (SQLException ignored) {
            // Best effort: the connection is about to be closed either way.
        }
    }

    private static Question mapQuestion(ResultSet rs) throws SQLException {
        return new Question(rs.getLong("id"), rs.getLong("quiz_id"), rs.getLong("topic_id"),
                rs.getString("question_text"), rs.getInt("position"));
    }

    private static QuestionOption mapOption(ResultSet rs) throws SQLException {
        return new QuestionOption(rs.getLong("id"), rs.getLong("question_id"), rs.getString("option_text"),
                rs.getBoolean("is_correct"));
    }
}
