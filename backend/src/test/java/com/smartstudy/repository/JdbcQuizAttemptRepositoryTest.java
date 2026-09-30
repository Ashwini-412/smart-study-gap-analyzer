package com.smartstudy.repository;

import com.smartstudy.model.AttemptAnswer;
import com.smartstudy.model.QuizAttempt;
import com.smartstudy.model.Student;
import com.smartstudy.support.DbTestSupport;
import com.smartstudy.util.DuplicateResourceException;
import com.smartstudy.util.NotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-MySQL repository tests (skipped unless DB_PASSWORD is set). */
class JdbcQuizAttemptRepositoryTest {

    private Database db;
    private JdbcQuizAttemptRepository attempts;
    private JdbcStudentRepository students;
    private JdbcQuizRepository quizzes;
    private JdbcTopicRepository topics;
    private JdbcQuestionRepository questions;

    private String studentEmail;
    private long studentId;
    private long quizId;
    private long topicId;
    private long questionAId;
    private long correctOptionAId;
    private long wrongOptionAId;
    private long questionBId;
    private long correctOptionBId;

    private Long createdAttemptId;

    @BeforeEach
    void setUp() {
        db = DbTestSupport.databaseOrSkip();
        attempts = new JdbcQuizAttemptRepository(db);
        students = new JdbcStudentRepository(db);
        quizzes = new JdbcQuizRepository(db);
        topics = new JdbcTopicRepository(db);
        questions = new JdbcQuestionRepository(db);

        studentEmail = "attempt-test-" + UUID.randomUUID() + "@example.test";
        Student student = students.create("Attempt Tester", studentEmail, "h", "s");
        studentId = student.id();
        quizId = quizzes.create("Quiz-" + UUID.randomUUID(), null).id();
        topicId = topics.create("Topic-" + UUID.randomUUID()).id();

        QuestionRepository.Created qA = questions.createWithOptions(quizId, topicId, "2 + 2 = ?",
                List.of(new QuestionRepository.NewOption("3", false), new QuestionRepository.NewOption("4", true)));
        questionAId = qA.question().id();
        wrongOptionAId = qA.options().get(0).id();
        correctOptionAId = qA.options().get(1).id();

        QuestionRepository.Created qB = questions.createWithOptions(quizId, topicId, "3 + 3 = ?",
                List.of(new QuestionRepository.NewOption("6", true), new QuestionRepository.NewOption("5", false)));
        questionBId = qB.question().id();
        correctOptionBId = qB.options().get(0).id();
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (db != null && createdAttemptId != null) {
            DbTestSupport.deleteAttemptById(db, createdAttemptId); // must run before deleting the quiz (RESTRICT)
        }
        if (db != null) {
            DbTestSupport.deleteQuizById(db, quizId); // cascades to its questions/options
            DbTestSupport.deleteTopicById(db, topicId);
            DbTestSupport.deleteStudentByEmail(db, studentEmail);
        }
    }

    private QuizAttemptRepository.NewAnswer answered(long questionId, long optionId, boolean correct) {
        return new QuizAttemptRepository.NewAnswer(questionId, optionId, correct);
    }

    @Test
    void createWithAnswersPersistsAttemptAndAllAnswersTogether() {
        QuizAttemptRepository.Created created = attempts.createWithAnswers(studentId, quizId, 2, 1,
                new BigDecimal("50.00"),
                List.of(answered(questionAId, wrongOptionAId, false), answered(questionBId, correctOptionBId, true)));

        assertTrue(created.attempt().id() > 0);
        createdAttemptId = created.attempt().id();
        assertEquals(studentId, created.attempt().studentId());
        assertEquals(quizId, created.attempt().quizId());
        assertEquals(2, created.attempt().totalQuestions());
        assertEquals(1, created.attempt().correctCount());
        assertEquals(0, new BigDecimal("50.00").compareTo(created.attempt().scorePercent()));
        assertTrue(created.attempt().submittedAt() != null);
        assertEquals(2, created.answers().size());

        QuizAttempt found = attempts.findById(created.attempt().id()).orElseThrow();
        assertEquals(studentId, found.studentId());

        List<AttemptAnswer> answers = attempts.findAnswersByAttemptId(created.attempt().id());
        assertEquals(2, answers.size());
        assertEquals(questionAId, answers.get(0).questionId());
        assertEquals(wrongOptionAId, answers.get(0).selectedOptionId());
        assertEquals(false, answers.get(0).correct());
        assertEquals(questionBId, answers.get(1).questionId());
        assertEquals(true, answers.get(1).correct());
    }

    @Test
    void unansweredQuestionStoresANullSelectedOption() {
        QuizAttemptRepository.Created created = attempts.createWithAnswers(studentId, quizId, 1, 0,
                new BigDecimal("0.00"), List.of(new QuizAttemptRepository.NewAnswer(questionAId, null, false)));
        createdAttemptId = created.attempt().id();

        AttemptAnswer answer = attempts.findAnswersByAttemptId(created.attempt().id()).get(0);
        assertNull(answer.selectedOptionId());
        assertEquals(false, answer.correct());
    }

    @Test
    void findByIdOnUnknownAttemptIsEmpty() {
        assertTrue(attempts.findById(Long.MAX_VALUE).isEmpty());
    }

    @Test
    void findAnswersForUnknownOrEmptyAttemptIsEmpty() {
        assertTrue(attempts.findAnswersByAttemptId(Long.MAX_VALUE).isEmpty());
    }

    @Test
    void findByStudentIdReturnsAttemptsMostRecentFirst() throws Exception {
        QuizAttemptRepository.Created first = attempts.createWithAnswers(studentId, quizId, 1, 1,
                new BigDecimal("100.00"), List.of(answered(questionAId, correctOptionAId, true)));
        Thread.sleep(1100); // submitted_at has second precision; ensure a strictly later timestamp
        QuizAttemptRepository.Created second = attempts.createWithAnswers(studentId, quizId, 1, 0,
                new BigDecimal("0.00"), List.of(answered(questionAId, wrongOptionAId, false)));

        try {
            List<QuizAttempt> found = attempts.findByStudentId(studentId);
            assertEquals(2, found.size());
            assertEquals(second.attempt().id(), found.get(0).id(), "most recent first");
            assertEquals(first.attempt().id(), found.get(1).id());
        } finally {
            DbTestSupport.deleteAttemptById(db, first.attempt().id());
            createdAttemptId = second.attempt().id();
        }
    }

    @Test
    void findByStudentIdForUnknownStudentIsEmpty() {
        assertTrue(attempts.findByStudentId(Long.MAX_VALUE).isEmpty());
    }

    // ---- history summaries (Milestone 11) ----

    @Test
    void summariesCountOnlyAnsweredRowsAndReturnStoredValues() {
        QuizAttemptRepository.Created created = attempts.createWithAnswers(studentId, quizId, 2, 1,
                new BigDecimal("50.00"), List.of(answered(questionAId, correctOptionAId, true),
                        new QuizAttemptRepository.NewAnswer(questionBId, null, false)));
        createdAttemptId = created.attempt().id();

        List<QuizAttemptRepository.AttemptSummary> summaries = attempts.findSummariesByStudentId(studentId);
        assertEquals(1, summaries.size());
        QuizAttemptRepository.AttemptSummary s = summaries.get(0);
        assertEquals(createdAttemptId, s.attempt().id());
        assertEquals(1, s.answeredCount(), "NULL selection is not counted");
        assertEquals(2, s.attempt().totalQuestions());
        assertEquals(1, s.attempt().correctCount());
        assertEquals(0, new BigDecimal("50.00").compareTo(s.attempt().scorePercent()));
        assertEquals(created.attempt().submittedAt(), s.attempt().submittedAt());
    }

    @Test
    void summariesAreScopedToTheStudentAndNewestFirst() throws Exception {
        QuizAttemptRepository.Created first = attempts.createWithAnswers(studentId, quizId, 1, 1,
                new BigDecimal("100.00"), List.of(answered(questionAId, correctOptionAId, true)));
        QuizAttemptRepository.Created second = attempts.createWithAnswers(studentId, quizId, 1, 0,
                new BigDecimal("0.00"), List.of(answered(questionAId, wrongOptionAId, false)));
        try {
            List<Long> ids = attempts.findSummariesByStudentId(studentId).stream().map(s -> s.attempt().id()).toList();
            // Same-second submissions tie on submitted_at; the id tie-breaker keeps the order stable.
            assertEquals(List.of(second.attempt().id(), first.attempt().id()), ids);
            assertTrue(attempts.findSummariesByStudentId(studentId).stream()
                    .allMatch(s -> s.attempt().studentId() == studentId));
            assertTrue(attempts.findSummariesByStudentId(Long.MAX_VALUE).isEmpty(), "other student: nothing");
        } finally {
            DbTestSupport.deleteAttemptById(db, first.attempt().id());
            createdAttemptId = second.attempt().id();
        }
    }

    @Test
    void summariesForAStudentWithNoAttemptsIsEmpty() {
        assertTrue(attempts.findSummariesByStudentId(studentId).isEmpty());
    }

    // ---- foreign-key handling ----

    @Test
    void unknownStudentIsRejectedAsNotFoundAndNothingIsPersisted() {
        assertThrows(NotFoundException.class, () -> attempts.createWithAnswers(Long.MAX_VALUE, quizId, 1, 1,
                new BigDecimal("100.00"), List.of(answered(questionAId, correctOptionAId, true))));
        assertTrue(attempts.findByStudentId(Long.MAX_VALUE).isEmpty());
    }

    @Test
    void unknownQuizIsRejectedAsNotFound() {
        assertThrows(NotFoundException.class, () -> attempts.createWithAnswers(studentId, Long.MAX_VALUE, 1, 1,
                new BigDecimal("100.00"), List.of(answered(questionAId, correctOptionAId, true))));
        assertTrue(attempts.findByStudentId(studentId).isEmpty());
    }

    @Test
    void unknownQuestionRollsBackTheWholeAttempt() {
        assertThrows(NotFoundException.class, () -> attempts.createWithAnswers(studentId, quizId, 2, 1,
                new BigDecimal("50.00"),
                List.of(answered(questionAId, correctOptionAId, true),
                        answered(Long.MAX_VALUE, correctOptionBId, true))));
        assertTrue(attempts.findByStudentId(studentId).isEmpty(), "no partial attempt after a failed creation");
    }

    @Test
    void optionNotBelongingToItsQuestionIsRejectedByTheCompositeForeignKey() {
        // correctOptionBId belongs to questionB, not questionA: the composite FK must reject this.
        assertThrows(NotFoundException.class, () -> attempts.createWithAnswers(studentId, quizId, 1, 1,
                new BigDecimal("100.00"), List.of(answered(questionAId, correctOptionBId, true))));
        assertTrue(attempts.findByStudentId(studentId).isEmpty());
    }

    // ---- duplicate constraint ----

    @Test
    void answeringTheSameQuestionTwiceInOneAttemptIsRejectedAsDuplicate() {
        assertThrows(DuplicateResourceException.class, () -> attempts.createWithAnswers(studentId, quizId, 2, 2,
                new BigDecimal("100.00"),
                List.of(answered(questionAId, correctOptionAId, true), answered(questionAId, correctOptionAId, true))));
        assertTrue(attempts.findByStudentId(studentId).isEmpty(), "no partial attempt after a rejected duplicate");
    }
}
