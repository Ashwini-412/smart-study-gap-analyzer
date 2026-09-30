package com.smartstudy.repository;

import com.smartstudy.model.Topic;
import com.smartstudy.util.DuplicateResourceException;

import java.util.List;
import java.util.Optional;

public interface TopicRepository {

    /** All topics, ordered by name. */
    List<Topic> findAll();

    Optional<Topic> findById(long id);

    /** Inserts a topic. {@code name} must already be normalised (trimmed). */
    Topic create(String name) throws DuplicateResourceException;
}
