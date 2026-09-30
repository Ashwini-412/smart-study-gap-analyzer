package com.smartstudy.model;

/**
 * A row of attempt_answers. {@code selectedOptionId} is null when the question was left
 * unanswered. {@code correct} is the grading snapshot taken at submission time, so history stays
 * stable if the quiz is edited later.
 */
public record AttemptAnswer(long id, long attemptId, long questionId, Long selectedOptionId, boolean correct) {
}
