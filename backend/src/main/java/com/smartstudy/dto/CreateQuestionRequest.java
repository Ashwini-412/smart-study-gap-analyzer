package com.smartstudy.dto;

import java.util.List;

public record CreateQuestionRequest(Long topicId, String questionText, List<OptionInput> options) {

    public record OptionInput(String text, Boolean correct) {
    }
}
