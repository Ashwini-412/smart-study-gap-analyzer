package com.smartstudy.dto;

import java.math.BigDecimal;

/**
 * One topic in GET /api/performance/gaps. {@code totalQuestions} counts every question of this topic
 * across all of the student's attempts (answered or not); {@code accuracyPercent} is null and
 * {@code classification} is "No Data" when that count is zero.
 */
public record TopicGapResponse(long topicId, String topicName, int totalQuestions, int correctCount,
                               BigDecimal accuracyPercent, String classification) {
}
