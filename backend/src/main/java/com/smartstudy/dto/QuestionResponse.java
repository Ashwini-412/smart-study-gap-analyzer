package com.smartstudy.dto;

import java.util.List;

/**
 * The quiz-taking representation of a question: never includes which option is correct.
 * See dto.QuestionAdminResponse for the representation returned to the creator of a question.
 */
public record QuestionResponse(long id, String questionText, int position, List<Option> options) {

    public record Option(long id, String text) {
    }
}
