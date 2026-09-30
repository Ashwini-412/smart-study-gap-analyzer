package com.smartstudy.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * GET /api/attempts/{id}: the evaluation stored at submission time (Milestone 9), for the owning
 * student only. Per-question outcomes say whether the student's own selection was correct; they
 * deliberately do not include the correct option's id, so the response is not an answer key.
 */
public record AttemptResultResponse(long id, long quizId, String submittedAt, int totalQuestions, int answeredCount,
                                    int correctCount, BigDecimal scorePercent, List<Answer> answers) {

    /** selectedOptionId is null when the question was left unanswered (which counts as incorrect). */
    public record Answer(long questionId, Long selectedOptionId, boolean correct) {
    }
}
