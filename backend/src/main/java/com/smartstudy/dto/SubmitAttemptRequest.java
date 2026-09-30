package com.smartstudy.dto;

import java.util.List;

/**
 * Body of POST /api/quizzes/{id}/attempts. Deliberately has no studentId, score, correct count or
 * per-answer correctness: identity comes from the session and evaluation is done on the server.
 * Unknown JSON properties are rejected by the parser, so a client sending any of those gets a 400.
 */
public record SubmitAttemptRequest(List<AnswerInput> answers) {

    /** selectedOptionId may be null: the question was left unanswered. */
    public record AnswerInput(Long questionId, Long selectedOptionId) {
    }
}
