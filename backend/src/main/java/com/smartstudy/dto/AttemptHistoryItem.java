package com.smartstudy.dto;

import java.math.BigDecimal;

/**
 * One entry of GET /api/attempts: an attempt's stored evaluation, as saved at submission time.
 * No per-question detail (that is GET /api/attempts/{id}) and no student id (always the caller).
 */
public record AttemptHistoryItem(long id, long quizId, String submittedAt, int totalQuestions, int answeredCount,
                                 int correctCount, BigDecimal scorePercent) {
}
