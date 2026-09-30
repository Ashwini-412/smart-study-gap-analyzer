package com.smartstudy.repository;

import com.smartstudy.model.AttemptAnswer;
import com.smartstudy.model.QuizAttempt;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for quiz_attempts / attempt_answers. The schema has no "in progress" state: every
 * NOT NULL column on quiz_attempts (total_questions, correct_count, score_percent) is fixed at
 * submission time, so an attempt and all of its answers are written together, atomically, by
 * {@link #createWithAnswers}. Score calculation and grading are service-layer concerns for a
 * later milestone; this layer only persists the outcome it is given.
 */
public interface QuizAttemptRepository {

    Optional<QuizAttempt> findById(long id);

    /** A student's attempts, most recent first. */
    List<QuizAttempt> findByStudentId(long studentId);

    /** Answers for one attempt, in the order they were recorded. */
    List<AttemptAnswer> findAnswersByAttemptId(long attemptId);

    /**
     * A student's attempts with how many questions each actually answered (a stored answer row with
     * a selected option), most recent first (submitted_at, then id). One query regardless of how
     * many attempts there are. Every value is what was stored at submission time.
     */
    List<AttemptSummary> findSummariesByStudentId(long studentId);

    /**
     * Creates the attempt and all of its answers as one atomic operation: either every row is
     * written, or none is. A foreign-key violation (unknown student/quiz/question, or an option
     * that does not belong to the question it answers) is reported as
     * {@link com.smartstudy.util.NotFoundException}; answering the same question twice in one
     * attempt is reported as {@link com.smartstudy.util.DuplicateResourceException}.
     */
    Created createWithAnswers(long studentId, long quizId, int totalQuestions, int correctCount,
                               BigDecimal scorePercent, List<NewAnswer> answers);

    /** One answer to record: not yet a persisted row, so it has no id. selectedOptionId may be null (unanswered). */
    record NewAnswer(long questionId, Long selectedOptionId, boolean correct) {
    }

    /** The attempt and its answers exactly as persisted, including their generated ids. */
    record Created(QuizAttempt attempt, List<AttemptAnswer> answers) {
    }

    /** An attempt plus its count of answered (non-null selection) answer rows. */
    record AttemptSummary(QuizAttempt attempt, int answeredCount) {
    }
}
