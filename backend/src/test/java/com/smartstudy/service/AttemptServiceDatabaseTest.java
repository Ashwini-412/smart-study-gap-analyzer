package com.smartstudy.service;

import com.smartstudy.dto.AttemptSubmissionResponse;
import com.smartstudy.repository.Database;
import com.smartstudy.repository.JdbcQuestionRepository;
import com.smartstudy.repository.JdbcQuizAttemptRepository;
import com.smartstudy.repository.JdbcQuizRepository;
import com.smartstudy.repository.JdbcStudentRepository;
import com.smartstudy.repository.JdbcTopicRepository;
import com.smartstudy.repository.QuestionRepository;
import com.smartstudy.service.AttemptService.AnswerSubmission;
import com.smartstudy.support.DbTestSupport;
import com.smartstudy.util.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Submission through the real JDBC repositories (skipped unless DB_PASSWORD is set). Rollback of a
 * failing answer insert is proven at the repository level in JdbcQuizAttemptRepositoryTest.
 */
class AttemptServiceDatabaseTest {

    private Database db;
    private AttemptService service;
    private String email;
    private long studentId;
    private long quizId;
    private long topicId;
    private long questionAId;
    private long correctA;
    private long questionBId;
    private Long attemptId;

    @BeforeEach
    void setUp() {
        db = DbTestSupport.databaseOrSkip();
        JdbcQuizRepository quizzes = new JdbcQuizRepository(db);
        JdbcQuestionRepository questions = new JdbcQuestionRepository(db);
        service = new AttemptService(quizzes, questions, new JdbcQuizAttemptRepository(db));

        email = "attempt-db-" + UUID.randomUUID() + "@example.test";
        studentId = new JdbcStudentRepository(db).create("Attempt DB Tester", email, "h", "s").id();
        quizId = quizzes.create("Quiz-" + UUID.randomUUID(), null).id();
        topicId = new JdbcTopicRepository(db).create("Topic-" + UUID.randomUUID()).id();

        QuestionRepository.Created a = questions.createWithOptions(quizId, topicId, "A",
                List.of(new QuestionRepository.NewOption("wrong", false), new QuestionRepository.NewOption("right", true)));
        questionAId = a.question().id();
        correctA = a.options().get(1).id();
        questionBId = questions.createWithOptions(quizId, topicId, "B",
                List.of(new QuestionRepository.NewOption("right", true), new QuestionRepository.NewOption("wrong", false)))
                .question().id();
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (db == null) {
            return;
        }
        if (attemptId != null) {
            DbTestSupport.deleteAttemptById(db, attemptId); // before the quiz: quizzes.id is RESTRICT
        }
        DbTestSupport.deleteQuizById(db, quizId);
        DbTestSupport.deleteTopicById(db, topicId);
        DbTestSupport.deleteStudentByEmail(db, email);
    }

    @Test
    void submissionIsPersistedWithServerComputedEvaluation() throws Exception {
        AttemptSubmissionResponse r = service.submit(studentId, quizId, List.of(new AnswerSubmission(questionAId, correctA)));
        attemptId = r.id();

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT student_id, quiz_id, total_questions, correct_count, score_percent FROM quiz_attempts WHERE id = ?")) {
            ps.setLong(1, attemptId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(studentId, rs.getLong(1));
                assertEquals(quizId, rs.getLong(2));
                assertEquals(2, rs.getInt(3));
                assertEquals(1, rs.getInt(4));
                assertEquals(0, new java.math.BigDecimal("50.00").compareTo(rs.getBigDecimal(5)));
            }
        }
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT question_id, selected_option_id, is_correct FROM attempt_answers WHERE attempt_id = ? ORDER BY id")) {
            ps.setLong(1, attemptId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(questionAId, rs.getLong(1));
                assertEquals(correctA, rs.getLong(2));
                assertTrue(rs.getBoolean(3));
                assertTrue(rs.next());
                assertEquals(questionBId, rs.getLong(1));
                rs.getLong(2);
                assertTrue(rs.wasNull(), "unanswered question stored with NULL selection");
                assertEquals(false, rs.getBoolean(3));
                assertEquals(false, rs.next());
            }
        }
    }

    @Test
    void rejectedSubmissionWritesNoRows() throws Exception {
        assertThrows(ValidationException.class, () -> service.submit(studentId, quizId, List.of(
                new AnswerSubmission(questionAId, correctA), new AnswerSubmission(questionAId, correctA))));
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM quiz_attempts WHERE student_id = ?")) {
            ps.setLong(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        }
    }
}
