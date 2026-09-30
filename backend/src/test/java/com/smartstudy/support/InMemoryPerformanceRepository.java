package com.smartstudy.support;

import com.smartstudy.model.AttemptAnswer;
import com.smartstudy.model.QuizAttempt;
import com.smartstudy.model.Topic;
import com.smartstudy.repository.PerformanceRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirrors JdbcPerformanceRepository's query over the in-memory repositories: every topic (ordered by
 * name, then id), counting this student's stored answer rows and their stored is_correct values.
 * The real query is covered against MySQL in JdbcPerformanceRepositoryTest.
 */
public class InMemoryPerformanceRepository implements PerformanceRepository {

    private final InMemoryTopicRepository topics;
    private final InMemoryQuestionRepository questions;
    private final InMemoryQuizAttemptRepository attempts;

    public InMemoryPerformanceRepository(InMemoryTopicRepository topics, InMemoryQuestionRepository questions,
                                         InMemoryQuizAttemptRepository attempts) {
        this.topics = topics;
        this.questions = questions;
        this.attempts = attempts;
    }

    @Override
    public List<TopicStats> topicStatsForStudent(long studentId) {
        Map<Long, int[]> byTopic = new HashMap<>(); // topicId -> {total, correct}
        for (QuizAttempt attempt : attempts.findByStudentId(studentId)) {
            for (AttemptAnswer a : attempts.findAnswersByAttemptId(attempt.id())) {
                int[] counts = byTopic.computeIfAbsent(questions.topicIdOf(a.questionId()), k -> new int[2]);
                counts[0]++;
                if (a.correct()) {
                    counts[1]++;
                }
            }
        }
        List<TopicStats> out = new ArrayList<>();
        for (Topic t : topics.findAll()) { // ordered by name; names are unique
            int[] counts = byTopic.getOrDefault(t.id(), new int[2]);
            out.add(new TopicStats(t.id(), t.name(), counts[0], counts[1]));
        }
        return out;
    }
}
