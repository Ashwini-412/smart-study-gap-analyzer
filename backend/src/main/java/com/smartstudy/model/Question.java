package com.smartstudy.model;

/** A row of questions. {@code position} is 1-based and unique within its quiz. */
public record Question(long id, long quizId, long topicId, String questionText, int position) {
}
