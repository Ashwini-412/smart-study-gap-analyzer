package com.smartstudy.service;

import com.smartstudy.dto.TopicGapResponse;
import com.smartstudy.repository.PerformanceRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Topic-level study-gap analysis (docs/ARCHITECTURE.md section 7):
 * <pre>
 *   accuracy = correct answers / total questions * 100   (per topic, cumulative over all attempts)
 *   accuracy >= strong                  -> Strong
 *   moderate <= accuracy < strong       -> Moderate
 *   accuracy < moderate                 -> Needs Improvement
 *   no questions answered in topic yet  -> No Data (not classified)
 * </pre>
 * An unanswered question counts as a question and as incorrect. Thresholds come from configuration
 * (gap.threshold.strong / gap.threshold.moderate); nothing here hardcodes them. Classification
 * compares the exact ratio, so rounding the displayed percentage never moves a topic across a
 * boundary.
 */
public class PerformanceService {

    static final String STRONG = "Strong";
    static final String MODERATE = "Moderate";
    static final String NEEDS_IMPROVEMENT = "Needs Improvement";
    static final String NO_DATA = "No Data";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PerformanceRepository performance;
    private final BigDecimal strong;
    private final BigDecimal moderate;

    public PerformanceService(PerformanceRepository performance, double strongThreshold, double moderateThreshold) {
        if (!(0 <= moderateThreshold && moderateThreshold < strongThreshold && strongThreshold <= 100)) {
            throw new IllegalArgumentException("Thresholds must satisfy 0 <= moderate < strong <= 100");
        }
        this.performance = performance;
        this.strong = BigDecimal.valueOf(strongThreshold);
        this.moderate = BigDecimal.valueOf(moderateThreshold);
    }

    /** Every topic with the student's cumulative accuracy and classification, ordered by topic name. */
    public List<TopicGapResponse> gaps(long studentId) {
        return performance.topicStatsForStudent(studentId).stream().map(this::toResponse).toList();
    }

    private TopicGapResponse toResponse(PerformanceRepository.TopicStats s) {
        if (s.totalQuestions() == 0) {
            return new TopicGapResponse(s.topicId(), s.topicName(), 0, 0, null, NO_DATA);
        }
        BigDecimal accuracy = BigDecimal.valueOf(s.correctCount()).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(s.totalQuestions()), 2, RoundingMode.HALF_UP);
        return new TopicGapResponse(s.topicId(), s.topicName(), s.totalQuestions(), s.correctCount(), accuracy,
                classify(s.correctCount(), s.totalQuestions()));
    }

    /** Exact comparison: correct / total * 100 >= t  <=>  correct * 100 >= t * total. */
    private String classify(int correct, int total) {
        BigDecimal scaledCorrect = BigDecimal.valueOf(correct).multiply(HUNDRED);
        BigDecimal totalDecimal = BigDecimal.valueOf(total);
        if (scaledCorrect.compareTo(strong.multiply(totalDecimal)) >= 0) {
            return STRONG;
        }
        if (scaledCorrect.compareTo(moderate.multiply(totalDecimal)) >= 0) {
            return MODERATE;
        }
        return NEEDS_IMPROVEMENT;
    }
}
