package com.smartstudy.repository;

import com.smartstudy.model.Quiz;
import com.smartstudy.model.Topic;
import com.smartstudy.util.DuplicateResourceException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface QuizRepository {

    /** All quizzes, ordered by id (creation order). */
    List<Quiz> findAll();

    Optional<Quiz> findById(long id);

    /** Inserts a quiz. {@code title} must already be normalised (trimmed). */
    Quiz create(String title, String description) throws DuplicateResourceException;

    /**
     * The distinct topics reached (through questions) by each of the given quizzes, keyed by
     * quiz id. A quiz with no questions yet, or one not present in {@code quizIds}, is omitted;
     * callers treat a missing key as "no topics". One round trip regardless of how many quizzes.
     */
    Map<Long, List<Topic>> topicsByQuizIds(Collection<Long> quizIds);
}
