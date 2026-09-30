package com.smartstudy.dto;

import java.util.List;

/** A quiz plus the distinct topics reached by its questions (a quiz can span several topics). */
public record QuizResponse(long id, String title, String description, String createdAt, List<TopicResponse> topics) {
}
