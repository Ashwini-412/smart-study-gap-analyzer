package com.smartstudy.dto;

/**
 * Returned after a successful submission. Contains no score and no per-question correctness, so it
 * cannot leak the answer key; results/review is a later milestone.
 */
public record AttemptSubmissionResponse(long id, long quizId, String submittedAt, int totalQuestions,
                                        int answeredCount) {
}
