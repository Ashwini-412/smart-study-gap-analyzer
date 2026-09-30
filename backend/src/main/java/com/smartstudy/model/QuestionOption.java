package com.smartstudy.model;

/**
 * A row of question_options. {@code correct} must never reach a quiz-taking response;
 * see dto.QuestionResponse vs dto.QuestionAdminResponse.
 */
public record QuestionOption(long id, long questionId, String optionText, boolean correct) {
}
