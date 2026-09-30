package com.smartstudy.service;

import com.smartstudy.dto.TopicGapResponse;
import com.smartstudy.repository.PerformanceRepository;
import com.smartstudy.repository.PerformanceRepository.TopicStats;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Formula, boundaries and threshold configuration; the query itself is tested elsewhere. */
class PerformanceServiceTest {

    private static final double STRONG = 75;   // application.properties defaults, passed in like AppConfig does
    private static final double MODERATE = 50;

    private static TopicGapResponse one(int correct, int total, double strong, double moderate) {
        PerformanceRepository repo = studentId -> List.of(new TopicStats(1, "Algebra", total, correct));
        return new PerformanceService(repo, strong, moderate).gaps(7L).get(0);
    }

    private static TopicGapResponse one(int correct, int total) {
        return one(correct, total, STRONG, MODERATE);
    }

    private static void assertAccuracy(String expected, TopicGapResponse r) {
        assertEquals(0, new BigDecimal(expected).compareTo(r.accuracyPercent()),
                "expected " + expected + " but was " + r.accuracyPercent());
    }

    // ---- arithmetic ----

    @Test
    void accuracyIsCorrectOverTotalTimesHundredRoundedToTwoPlaces() {
        assertAccuracy("33.33", one(1, 3));
        assertAccuracy("66.67", one(2, 3));
        assertAccuracy("100.00", one(5, 5));
        assertAccuracy("0.00", one(0, 4));
        TopicGapResponse r = one(3, 8);
        assertEquals(8, r.totalQuestions());
        assertEquals(3, r.correctCount());
        assertAccuracy("37.50", r);
    }

    // ---- default boundaries (docs/ARCHITECTURE.md section 7) ----

    @Test
    void exactly75IsStrong() {
        TopicGapResponse r = one(3, 4);
        assertAccuracy("75.00", r);
        assertEquals("Strong", r.classification());
        assertEquals("Strong", one(10, 10).classification());
    }

    @Test
    void justBelow75IsModerate() {
        assertEquals("Moderate", one(149, 200).classification(), "74.5% is Moderate (< strong, not <= 74)");
    }

    @Test
    void classificationUsesTheExactRatioNotTheRoundedDisplay() {
        // 14999/20000 = 74.995% displays as 75.00 but is strictly below the 75 threshold.
        TopicGapResponse r = one(14_999, 20_000);
        assertAccuracy("75.00", r);
        assertEquals("Moderate", r.classification());
    }

    @Test
    void exactly50IsModerate() {
        TopicGapResponse r = one(1, 2);
        assertAccuracy("50.00", r);
        assertEquals("Moderate", r.classification());
    }

    @Test
    void below50IsNeedsImprovement() {
        assertEquals("Needs Improvement", one(99, 200).classification(), "49.5%");
        assertEquals("Needs Improvement", one(0, 3).classification(), "0%");
    }

    // ---- no history ----

    @Test
    void topicWithNoAnswersIsNoDataAndNotClassified() {
        TopicGapResponse r = one(0, 0);
        assertEquals(0, r.totalQuestions());
        assertEquals(0, r.correctCount());
        assertNull(r.accuracyPercent());
        assertEquals("No Data", r.classification());
    }

    // ---- thresholds come from configuration ----

    @Test
    void configuredThresholdsAreUsedNotHardcodedOnes() {
        assertEquals("Moderate", one(3, 4, 80, 60).classification(), "75% is Moderate when strong=80");
        assertEquals("Strong", one(4, 5, 80, 60).classification(), "80% is Strong when strong=80");
        assertEquals("Moderate", one(3, 5, 80, 60).classification(), "60% is Moderate when moderate=60");
        assertEquals("Needs Improvement", one(55, 100, 80, 60).classification(), "55% is NI when moderate=60");
        assertEquals("Strong", one(1, 2, 50, 25).classification(), "50% is Strong when strong=50");
    }

    @Test
    void fractionalThresholdsAreComparedExactly() {
        assertEquals("Strong", one(2, 3, 66.66, 50).classification(), "66.666...% >= 66.66");
        assertEquals("Moderate", one(2, 3, 66.67, 50).classification(), "66.666...% < 66.67");
    }

    @Test
    void invalidThresholdsAreRejected() {
        PerformanceRepository repo = studentId -> List.of();
        assertThrows(IllegalArgumentException.class, () -> new PerformanceService(repo, 50, 50));
        assertThrows(IllegalArgumentException.class, () -> new PerformanceService(repo, 50, 75));
        assertThrows(IllegalArgumentException.class, () -> new PerformanceService(repo, 101, 50));
        assertThrows(IllegalArgumentException.class, () -> new PerformanceService(repo, 75, -1));
    }

    // ---- ordering and scoping are passed through from the repository ----

    @Test
    void repositoryOrderIsPreservedAndTheStudentIdIsPassedThrough() {
        long[] seen = new long[1];
        PerformanceRepository repo = studentId -> {
            seen[0] = studentId;
            return List.of(new TopicStats(2, "Algebra", 4, 3), new TopicStats(1, "Geometry", 0, 0));
        };
        List<TopicGapResponse> gaps = new PerformanceService(repo, STRONG, MODERATE).gaps(42L);
        assertEquals(42L, seen[0]);
        assertEquals(List.of("Algebra", "Geometry"), gaps.stream().map(TopicGapResponse::topicName).toList());
    }
}
