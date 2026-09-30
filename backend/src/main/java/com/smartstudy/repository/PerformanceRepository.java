package com.smartstudy.repository;

import java.util.List;

/**
 * Read-only aggregations over a student's stored attempt data. Nothing here is persisted: topic
 * accuracy is derived at query time (docs/ARCHITECTURE.md section 5).
 */
public interface PerformanceRepository {

    /**
     * One row per topic (every topic, including ones the student never answered), ordered by topic
     * name then id. {@code totalQuestions} counts every stored answer row of the student's attempts
     * whose question belongs to the topic - answered or not, across all attempts (cumulative);
     * {@code correctCount} counts those stored with is_correct = TRUE. Only {@code studentId}'s
     * attempts contribute. One query.
     */
    List<TopicStats> topicStatsForStudent(long studentId);

    record TopicStats(long topicId, String topicName, int totalQuestions, int correctCount) {
    }
}
