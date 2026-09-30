package com.smartstudy.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A row of quiz_attempts: one row per submission, written once with its final score. The schema
 * has no "in progress" state (every column here is NOT NULL), so there is nothing to update after
 * creation.
 */
public record QuizAttempt(long id, long studentId, long quizId, int totalQuestions, int correctCount,
                           BigDecimal scorePercent, Instant submittedAt) {
}
