package com.smartstudy.repository;

import com.smartstudy.model.AttemptAnswer;
import com.smartstudy.model.QuizAttempt;
import com.smartstudy.util.DuplicateResourceException;
import com.smartstudy.util.NotFoundException;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JdbcQuizAttemptRepository implements QuizAttemptRepository {

    private static final int MYSQL_DUPLICATE_ENTRY = 1062;
    private static final int MYSQL_FK_CONSTRAINT_FAILS = 1452;

    private static final String SELECT_BY_ID =
            "SELECT id, student_id, quiz_id, total_questions, correct_count, score_percent, submitted_at "
                    + "FROM quiz_attempts WHERE id = ?";
    private static final String SELECT_BY_STUDENT =
            "SELECT id, student_id, quiz_id, total_questions, correct_count, score_percent, submitted_at "
                    + "FROM quiz_attempts WHERE student_id = ? ORDER BY submitted_at DESC, id DESC";
    // LEFT JOIN so an attempt is listed even with no answer rows; COUNT(column) skips NULLs, so an
    // unanswered question (selected_option_id NULL) is not counted. GROUP BY the primary key is
    // valid under ONLY_FULL_GROUP_BY because the other quiz_attempts columns depend on it.
    private static final String SELECT_SUMMARIES_BY_STUDENT =
            "SELECT a.id, a.student_id, a.quiz_id, a.total_questions, a.correct_count, a.score_percent, "
                    + "a.submitted_at, COUNT(aa.selected_option_id) AS answered_count "
                    + "FROM quiz_attempts a LEFT JOIN attempt_answers aa ON aa.attempt_id = a.id "
                    + "WHERE a.student_id = ? GROUP BY a.id ORDER BY a.submitted_at DESC, a.id DESC";
    private static final String SELECT_ANSWERS_BY_ATTEMPT =
            "SELECT id, attempt_id, question_id, selected_option_id, is_correct FROM attempt_answers "
                    + "WHERE attempt_id = ? ORDER BY id";
    private static final String INSERT_ATTEMPT =
            "INSERT INTO quiz_attempts (student_id, quiz_id, total_questions, correct_count, score_percent) "
                    + "VALUES (?, ?, ?, ?, ?)";
    private static final String INSERT_ANSWER =
            "INSERT INTO attempt_answers (attempt_id, question_id, selected_option_id, is_correct) "
                    + "VALUES (?, ?, ?, ?)";

    private final Database database;

    public JdbcQuizAttemptRepository(Database database) {
        this.database = database;
    }

    @Override
    public Optional<QuizAttempt> findById(long id) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_BY_ID)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapAttempt(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to query quiz attempts", e);
        }
    }

    @Override
    public List<QuizAttempt> findByStudentId(long studentId) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_BY_STUDENT)) {
            ps.setLong(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                List<QuizAttempt> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapAttempt(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to query quiz attempts", e);
        }
    }

    @Override
    public List<AttemptAnswer> findAnswersByAttemptId(long attemptId) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_ANSWERS_BY_ATTEMPT)) {
            ps.setLong(1, attemptId);
            try (ResultSet rs = ps.executeQuery()) {
                List<AttemptAnswer> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapAnswer(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to query attempt answers", e);
        }
    }

    @Override
    public List<AttemptSummary> findSummariesByStudentId(long studentId) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_SUMMARIES_BY_STUDENT)) {
            ps.setLong(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                List<AttemptSummary> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new AttemptSummary(mapAttempt(rs), rs.getInt("answered_count")));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to query quiz attempts", e);
        }
    }

    @Override
    public Created createWithAnswers(long studentId, long quizId, int totalQuestions, int correctCount,
                                      BigDecimal scorePercent, List<NewAnswer> answers) {
        try (Connection c = database.getConnection()) {
            try {
                c.setAutoCommit(false);
                long attemptId = insertAttempt(c, studentId, quizId, totalQuestions, correctCount, scorePercent);
                List<AttemptAnswer> saved = insertAnswers(c, attemptId, answers);
                c.commit();
                return new Created(findAttemptOnConnection(c, attemptId), saved);
            } catch (SQLException e) {
                rollbackQuietly(c);
                if (e.getErrorCode() == MYSQL_FK_CONSTRAINT_FAILS) {
                    throw new NotFoundException("Referenced student, quiz, question or option does not exist");
                }
                if (e.getErrorCode() == MYSQL_DUPLICATE_ENTRY) {
                    throw new DuplicateResourceException("This attempt already has an answer for that question");
                }
                throw new DataAccessException("Failed to create quiz attempt", e);
            } catch (RuntimeException e) {
                rollbackQuietly(c);
                throw e;
            } finally {
                resetAutoCommitQuietly(c);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to create quiz attempt", e);
        }
    }

    private long insertAttempt(Connection c, long studentId, long quizId, int totalQuestions, int correctCount,
                                BigDecimal scorePercent) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(INSERT_ATTEMPT, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, studentId);
            ps.setLong(2, quizId);
            ps.setInt(3, totalQuestions);
            ps.setInt(4, correctCount);
            ps.setBigDecimal(5, scorePercent);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private List<AttemptAnswer> insertAnswers(Connection c, long attemptId, List<NewAnswer> answers)
            throws SQLException {
        List<AttemptAnswer> saved = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(INSERT_ANSWER, Statement.RETURN_GENERATED_KEYS)) {
            for (NewAnswer answer : answers) {
                ps.setLong(1, attemptId);
                ps.setLong(2, answer.questionId());
                if (answer.selectedOptionId() == null) {
                    ps.setNull(3, Types.BIGINT);
                } else {
                    ps.setLong(3, answer.selectedOptionId());
                }
                ps.setBoolean(4, answer.correct());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    saved.add(new AttemptAnswer(keys.getLong(1), attemptId, answer.questionId(),
                            answer.selectedOptionId(), answer.correct()));
                }
            }
        }
        return saved;
    }

    private QuizAttempt findAttemptOnConnection(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SELECT_BY_ID)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return mapAttempt(rs);
            }
        }
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

    private static QuizAttempt mapAttempt(ResultSet rs) throws SQLException {
        Timestamp submittedAt = rs.getTimestamp("submitted_at");
        return new QuizAttempt(rs.getLong("id"), rs.getLong("student_id"), rs.getLong("quiz_id"),
                rs.getInt("total_questions"), rs.getInt("correct_count"), rs.getBigDecimal("score_percent"),
                submittedAt == null ? null : submittedAt.toInstant());
    }

    private static AttemptAnswer mapAnswer(ResultSet rs) throws SQLException {
        long selectedOptionId = rs.getLong("selected_option_id");
        Long selected = rs.wasNull() ? null : selectedOptionId;
        return new AttemptAnswer(rs.getLong("id"), rs.getLong("attempt_id"), rs.getLong("question_id"), selected,
                rs.getBoolean("is_correct"));
    }
}
