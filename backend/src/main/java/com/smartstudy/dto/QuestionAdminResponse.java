package com.smartstudy.dto;

import java.util.List;

/**
 * The administrative representation of a freshly created question: includes which option is
 * correct, since this is returned only to whoever just submitted that answer key. Never reuse
 * this shape for quiz-taking; see dto.QuestionResponse.
 */
public record QuestionAdminResponse(long id, long quizId, long topicId, String questionText, int position,
                                     List<Option> options) {

    public record Option(long id, String text, boolean correct) {
    }
}
