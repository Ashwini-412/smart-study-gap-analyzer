package com.smartstudy.model;

import java.time.Instant;

/** A row of quizzes. Topics are not stored here: a quiz reaches its topics through its questions. */
public record Quiz(long id, String title, String description, Instant createdAt) {
}
