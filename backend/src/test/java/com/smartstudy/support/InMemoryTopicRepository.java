package com.smartstudy.support;

import com.smartstudy.model.Topic;
import com.smartstudy.repository.TopicRepository;
import com.smartstudy.util.DuplicateResourceException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryTopicRepository implements TopicRepository {

    private final Map<Long, Topic> byId = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    @Override
    public List<Topic> findAll() {
        return byId.values().stream().sorted(Comparator.comparing(Topic::name)).toList();
    }

    @Override
    public Optional<Topic> findById(long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public synchronized Topic create(String name) {
        boolean exists = byId.values().stream().anyMatch(t -> t.name().equalsIgnoreCase(name));
        if (exists) {
            throw new DuplicateResourceException("A topic with this name already exists");
        }
        Topic t = new Topic(ids.incrementAndGet(), name);
        byId.put(t.id(), t);
        return t;
    }

    public int count() {
        return byId.size();
    }
}
