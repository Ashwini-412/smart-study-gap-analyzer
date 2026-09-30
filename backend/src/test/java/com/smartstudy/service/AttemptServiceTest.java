package com.smartstudy.service;

import com.smartstudy.dto.CreateQuestionRequest;
import com.smartstudy.dto.CreateQuestionRequest.OptionInput;
import com.smartstudy.dto.QuestionAdminResponse;
import com.smartstudy.service.AttemptService.AnswerSubmission;
import com.smartstudy.support.InMemoryQuestionRepository;
import com.smartstudy.support.InMemoryQuizAttemptRepository;
import com.smartstudy.support.InMemoryQuizRepository;
import com.smartstudy.support.InMemoryTopicRepository;
import com.smartstudy.util.NotFoundException;
import com.smartstudy.util.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * validateSubmission has only (quizId, answers) as parameters - there is no studentId or score
 * parameter anywhere on AttemptService, so a caller structurally cannot pass a claimed identity
 * or a claimed score through it. Every test below calls it with exactly those two arguments.
 */
class AttemptServiceTest {

    private InMemoryQuizRepository quizzes;
    private InMemoryQuestionRepository questions;
    private InMemoryQuizAttemptRepository attempts;
    private QuestionService questionService;
    private AttemptService service;

    private long quizId;
    private long topicId;
    private long questionAId;
    private long correctOptionAId;
    private long wrongOptionAId;
    private long questionBId;
    private long correctOptionBId;

    @BeforeEach
    void setUp() {
        quizzes = new InMemoryQuizRepository();
        questions = new InMemoryQuestionRepository();
        InMemoryTopicRepository topics = new InMemoryTopicRepository();
        questionService = new QuestionService(questions, quizzes, topics);
        attempts = new InMemoryQuizAttemptRepository();
        service = new AttemptService(quizzes, questions, attempts);

        quizId = quizzes.create("Quiz A", null).id();
        topicId = topics.create("Algebra").id();

        QuestionAdminResponse qa = questionService.create(quizId, new CreateQuestionRequest(topicId, "2 + 2 = ?",
                List.of(new OptionInput("3", false), new OptionInput("4", true))));
        questionAId = qa.id();
        wrongOptionAId = qa.options().get(0).id();
        correctOptionAId = qa.options().get(1).id();

        QuestionAdminResponse qb = questionService.create(quizId, new CreateQuestionRequest(topicId, "3 + 3 = ?",
                List.of(new OptionInput("6", true), new OptionInput("5", false))));
        questionBId = qb.id();
        correctOptionBId = qb.options().get(0).id();
    }

    @Test
    void validSubmissionWithEveryQuestionAnsweredPasses() {
        assertDoesNotThrow(() -> service.validateSubmission(quizId, List.of(
                new AnswerSubmission(questionAId, correctOptionAId),
                new AnswerSubmission(questionBId, correctOptionBId))));
    }

    @Test
    void unansweredQuestionIsAllowed() {
        assertDoesNotThrow(() -> service.validateSubmission(quizId,
                List.of(new AnswerSubmission(questionAId, null))));
    }

    @Test
    void unknownQuizThrowsNotFound() {
        assertThrows(NotFoundException.class, () -> service.validateSubmission(999_999L,
                List.of(new AnswerSubmission(questionAId, correctOptionAId))));
    }

    @Test
    void nullAnswersIsRejected() {
        var e = assertThrows(ValidationException.class, () -> service.validateSubmission(quizId, null));
        assertTrue(e.fieldErrors().containsKey("answers"));
    }

    @Test
    void emptyAnswersIsRejected() {
        var e = assertThrows(ValidationException.class, () -> service.validateSubmission(quizId, List.of()));
        assertTrue(e.fieldErrors().containsKey("answers"));
    }

    @Test
    void missingQuestionIdIsRejected() {
        var e = assertThrows(ValidationException.class,
                () -> service.validateSubmission(quizId, List.of(new AnswerSubmission(null, correctOptionAId))));
        assertTrue(e.fieldErrors().containsKey("answers[0].questionId"));
    }

    @Test
    void questionNotBelongingToTheQuizIsRejected() {
        long otherQuizId = quizzes.create("Quiz B", null).id();
        var e = assertThrows(ValidationException.class,
                () -> service.validateSubmission(otherQuizId, List.of(new AnswerSubmission(questionAId, correctOptionAId))));
        assertTrue(e.fieldErrors().containsKey("answers[0].questionId"));
    }

    @Test
    void unknownQuestionIdIsRejected() {
        var e = assertThrows(ValidationException.class,
                () -> service.validateSubmission(quizId, List.of(new AnswerSubmission(999_999L, null))));
        assertTrue(e.fieldErrors().containsKey("answers[0].questionId"));
    }

    @Test
    void optionNotBelongingToTheQuestionIsRejected() {
        // correctOptionBId belongs to questionB, not questionA.
        var e = assertThrows(ValidationException.class, () -> service.validateSubmission(quizId,
                List.of(new AnswerSubmission(questionAId, correctOptionBId))));
        assertTrue(e.fieldErrors().containsKey("answers[0].selectedOptionId"));
    }

    @Test
    void unknownOptionIdIsRejected() {
        var e = assertThrows(ValidationException.class, () -> service.validateSubmission(quizId,
                List.of(new AnswerSubmission(questionAId, 999_999L))));
        assertTrue(e.fieldErrors().containsKey("answers[0].selectedOptionId"));
    }

    @Test
    void answeringTheSameQuestionTwiceInOneSubmissionIsRejected() {
        var e = assertThrows(ValidationException.class, () -> service.validateSubmission(quizId, List.of(
                new AnswerSubmission(questionAId, correctOptionAId),
                new AnswerSubmission(questionAId, wrongOptionAId))));
        assertTrue(e.fieldErrors().containsKey("answers[1].questionId"));
    }

    @Test
    void multipleViolationsAreAllReportedTogether() {
        var e = assertThrows(ValidationException.class, () -> service.validateSubmission(quizId, List.of(
                new AnswerSubmission(999_999L, null),
                new AnswerSubmission(questionBId, correctOptionAId))));
        assertEquals(2, e.fieldErrors().size());
        assertTrue(e.fieldErrors().containsKey("answers[0].questionId"));
        assertTrue(e.fieldErrors().containsKey("answers[1].selectedOptionId"));
    }

    @Test
    void nullAnswerElementIsRejectedAsValidationNotACrash() {
        var e = assertThrows(ValidationException.class, () -> service.validateSubmission(quizId,
                java.util.Arrays.asList(null, new AnswerSubmission(questionAId, correctOptionAId))));
        assertTrue(e.fieldErrors().containsKey("answers[0]"));
    }

    // ---- submit: evaluation and persistence ----

    @Test
    void submitStoresTheAttemptForTheGivenStudentWithAServerComputedScore() {
        var r = service.submit(7L, quizId, List.of(
                new AnswerSubmission(questionAId, correctOptionAId),
                new AnswerSubmission(questionBId, qbWrongOption())));

        assertEquals(2, r.totalQuestions());
        assertEquals(2, r.answeredCount());
        var stored = attempts.findById(r.id()).orElseThrow();
        assertEquals(7L, stored.studentId(), "stored under the id the caller passed (resolved from the session)");
        assertEquals(quizId, stored.quizId());
        assertEquals(1, stored.correctCount());
        assertEquals(0, new java.math.BigDecimal("50.00").compareTo(stored.scorePercent()));

        var answers = attempts.findAnswersByAttemptId(r.id());
        assertEquals(2, answers.size());
        assertEquals(questionAId, answers.get(0).questionId());
        assertTrue(answers.get(0).correct());
        assertEquals(questionBId, answers.get(1).questionId());
        assertEquals(false, answers.get(1).correct());
    }

    @Test
    void unansweredQuestionsAreStoredAndCountAsIncorrect() {
        var r = service.submit(7L, quizId, List.of(new AnswerSubmission(questionAId, correctOptionAId)));

        assertEquals(2, r.totalQuestions(), "total is the quiz's question count, not the number submitted");
        assertEquals(1, r.answeredCount());
        var stored = attempts.findById(r.id()).orElseThrow();
        assertEquals(1, stored.correctCount());
        assertEquals(0, new java.math.BigDecimal("50.00").compareTo(stored.scorePercent()));

        var unanswered = attempts.findAnswersByAttemptId(r.id()).get(1);
        assertEquals(questionBId, unanswered.questionId());
        assertEquals(null, unanswered.selectedOptionId());
        assertEquals(false, unanswered.correct());
    }

    @Test
    void explicitNullSelectionIsUnansweredAndIncorrect() {
        var r = service.submit(7L, quizId, List.of(
                new AnswerSubmission(questionAId, null), new AnswerSubmission(questionBId, correctOptionBId)));
        assertEquals(1, r.answeredCount());
        assertEquals(1, attempts.findById(r.id()).orElseThrow().correctCount());
    }

    @Test
    void scoreIsRoundedToTwoDecimalPlaces() {
        long q3 = quizzes.create("Quiz Thirds", null).id();
        long t = topicId;
        QuestionAdminResponse a = questionService.create(q3, new CreateQuestionRequest(t, "A",
                List.of(new OptionInput("x", true), new OptionInput("y", false))));
        questionService.create(q3, new CreateQuestionRequest(t, "B",
                List.of(new OptionInput("x", true), new OptionInput("y", false))));
        questionService.create(q3, new CreateQuestionRequest(t, "C",
                List.of(new OptionInput("x", true), new OptionInput("y", false))));

        var r = service.submit(7L, q3, List.of(new AnswerSubmission(a.id(), a.options().get(0).id())));
        assertEquals(0, new java.math.BigDecimal("33.33").compareTo(attempts.findById(r.id()).orElseThrow().scorePercent()));
    }

    @Test
    void invalidSubmissionPersistsNothing() {
        assertThrows(ValidationException.class, () -> service.submit(7L, quizId, List.of(
                new AnswerSubmission(questionAId, correctOptionAId),
                new AnswerSubmission(questionAId, wrongOptionAId))));
        assertThrows(NotFoundException.class, () -> service.submit(7L, 999_999L,
                List.of(new AnswerSubmission(questionAId, correctOptionAId))));
        assertThrows(ValidationException.class, () -> service.submit(7L, quizId,
                java.util.Arrays.asList((AnswerSubmission) null)));
        assertEquals(0, attempts.attemptCount());
    }

    @Test
    void persistenceFailureLeavesNoPartialAttempt() {
        attempts.failNextWrite(new com.smartstudy.repository.DataAccessException("simulated answer insert failure",
                new java.sql.SQLException("boom")));
        assertThrows(com.smartstudy.repository.DataAccessException.class, () -> service.submit(7L, quizId,
                List.of(new AnswerSubmission(questionAId, correctOptionAId))));
        assertEquals(0, attempts.attemptCount());
        assertEquals(0, attempts.answerCount());
    }

    // ---- getResult (Milestone 10) ----

    @Test
    void ownerGetsTheStoredEvaluation() {
        var submitted = service.submit(7L, quizId, List.of(new AnswerSubmission(questionAId, correctOptionAId)));

        var r = service.getResult(7L, submitted.id());
        assertEquals(submitted.id(), r.id());
        assertEquals(quizId, r.quizId());
        assertEquals(submitted.submittedAt(), r.submittedAt());
        assertEquals(2, r.totalQuestions());
        assertEquals(1, r.answeredCount());
        assertEquals(1, r.correctCount());
        assertEquals(0, new java.math.BigDecimal("50.00").compareTo(r.scorePercent()));
        assertEquals(2, r.answers().size());
        assertEquals(questionAId, r.answers().get(0).questionId());
        assertTrue(r.answers().get(0).correct());
        assertEquals(null, r.answers().get(1).selectedOptionId());
        assertEquals(false, r.answers().get(1).correct());
    }

    @Test
    void resultIsReadFromPersistedDataNotRecomputed() {
        // Stored values that the answer rows would NOT reproduce if the service re-scored them.
        var created = attempts.createWithAnswers(7L, quizId, 2, 1, new java.math.BigDecimal("42.00"), List.of(
                new com.smartstudy.repository.QuizAttemptRepository.NewAnswer(questionAId, correctOptionAId, true),
                new com.smartstudy.repository.QuizAttemptRepository.NewAnswer(questionBId, correctOptionBId, true)));

        var r = service.getResult(7L, created.attempt().id());
        assertEquals(1, r.correctCount());
        assertEquals(0, new java.math.BigDecimal("42.00").compareTo(r.scorePercent()));
    }

    @Test
    void anotherStudentsAttemptIsNotFound() {
        var submitted = service.submit(7L, quizId, List.of(new AnswerSubmission(questionAId, correctOptionAId)));
        var e = assertThrows(NotFoundException.class, () -> service.getResult(8L, submitted.id()));
        var unknown = assertThrows(NotFoundException.class, () -> service.getResult(8L, 999_999L));
        assertEquals(unknown.getMessage(), e.getMessage(), "indistinguishable from a missing attempt");
    }

    @Test
    void unknownAttemptIsNotFound() {
        assertThrows(NotFoundException.class, () -> service.getResult(7L, 999_999L));
    }

    // ---- history (Milestone 11) ----

    @Test
    void historyIsEmptyForAStudentWithNoAttempts() {
        assertTrue(service.history(7L).isEmpty());
    }

    @Test
    void historyListsOnlyTheStudentsOwnAttemptsNewestFirst() {
        var first = service.submit(7L, quizId, List.of(new AnswerSubmission(questionAId, correctOptionAId)));
        service.submit(8L, quizId, List.of(new AnswerSubmission(questionAId, correctOptionAId)));
        var second = service.submit(7L, quizId, List.of(new AnswerSubmission(questionAId, wrongOptionAId)));

        var h = service.history(7L);
        assertEquals(List.of(second.id(), first.id()), h.stream().map(i -> i.id()).toList());
        assertEquals(1, service.history(8L).size());
    }

    @Test
    void historyReturnsStoredValuesExactly() {
        var created = attempts.createWithAnswers(7L, quizId, 2, 1, new java.math.BigDecimal("42.00"), List.of(
                new com.smartstudy.repository.QuizAttemptRepository.NewAnswer(questionAId, correctOptionAId, true),
                new com.smartstudy.repository.QuizAttemptRepository.NewAnswer(questionBId, null, false)));

        var item = service.history(7L).get(0);
        assertEquals(created.attempt().id(), item.id());
        assertEquals(quizId, item.quizId());
        assertEquals(2, item.totalQuestions());
        assertEquals(1, item.answeredCount(), "the null selection is not counted as answered");
        assertEquals(1, item.correctCount());
        assertEquals(0, new java.math.BigDecimal("42.00").compareTo(item.scorePercent()), "not re-scored");
        assertEquals(created.attempt().submittedAt().toString(), item.submittedAt());
    }

    @Test
    void historyIsUnaffectedByLaterQuizEdits() {
        service.submit(7L, quizId, List.of(new AnswerSubmission(questionAId, correctOptionAId),
                new AnswerSubmission(questionBId, correctOptionBId)));
        var before = service.history(7L).get(0);

        questionService.create(quizId, new CreateQuestionRequest(topicId, "Added later",
                List.of(new OptionInput("x", true), new OptionInput("y", false))));

        var after = service.history(7L).get(0);
        assertEquals(before, after, "stored snapshot: totals and score do not follow the edited quiz");
        assertEquals(2, after.totalQuestions());
        assertEquals(0, new java.math.BigDecimal("100.00").compareTo(after.scorePercent()));
    }

    private long qbWrongOption() {
        return questions.optionsByQuestionIds(List.of(questionBId)).get(questionBId).stream()
                .filter(o -> !o.correct()).findFirst().orElseThrow().id();
    }
}
