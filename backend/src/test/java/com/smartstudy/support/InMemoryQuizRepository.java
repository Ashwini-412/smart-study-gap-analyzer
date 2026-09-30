package com.smartstudy.support;

import com.smartstudy.model.Quiz;
import com.smartstudy.model.Topic;
import com.smartstudy.repository.QuizRepository;
import com.smartstudy.util.DuplicateResourceException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryQuizRepository implements QuizRepository {

    private final Map<Long, Quiz> byId = new ConcurrentHashMap<>();
    /** Mirrors "topics reached through questions": populated directly by tests that need it. */
    private final Map<Long, List<Topic>> topicsByQuizId = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    @Override
    public List<Quiz> findAll() {
        return byId.values().stream().sorted(Comparator.comparingLong(Quiz::id)).toList();
    }

    @Override
    public Optional<Quiz> findById(long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public synchronized Quiz create(String title, String description) {
        boolean exists = byId.values().stream().anyMatch(q -> q.title().equalsIgnoreCase(title));
        if (exists) {
            throw new DuplicateResourceException("A quiz with this title already exists");
        }
        Quiz q = new Quiz(ids.incrementAndGet(), title, description, Instant.now());
        byId.put(q.id(), q);
        return q;
    }

    @Override
    public Map<Long, List<Topic>> topicsByQuizIds(Collection<Long> quizIds) {
        Map<Long, List<Topic>> result = new LinkedHashMap<>();
        for (Long id : quizIds) {
            List<Topic> topics = topicsByQuizId.get(id);
            if (topics != null && !topics.isEmpty()) {
                result.put(id, topics);
            }
        }
        return result;
    }

    /** Test seam: declares that {@code quizId} reaches {@code topics} (as JdbcQuizRepository derives via JOIN). */
    public void setTopicsForQuiz(long quizId, Topic... topics) {
        topicsByQuizId.put(quizId, new ArrayList<>(List.of(topics)));
    }

    public int count() {
        return byId.size();
    }
}
