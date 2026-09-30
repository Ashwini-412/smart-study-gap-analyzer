package com.smartstudy.support;

import com.smartstudy.model.AttemptAnswer;
import com.smartstudy.model.QuizAttempt;
import com.smartstudy.repository.QuizAttemptRepository;
import com.smartstudy.util.DuplicateResourceException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryQuizAttemptRepository implements QuizAttemptRepository {

    private final Map<Long, QuizAttempt> attemptsById = new ConcurrentHashMap<>();
    private final Map<Long, List<AttemptAnswer>> answersByAttemptId = new ConcurrentHashMap<>();
    private final AtomicLong attemptIds = new AtomicLong();
    private final AtomicLong answerIds = new AtomicLong();

    /** Set by a test to make the next createWithAnswers fail mid-write, to prove nothing is kept. */
    private volatile RuntimeException failNextWrite;

    @Override
    public Optional<QuizAttempt> findById(long id) {
        return Optional.ofNullable(attemptsById.get(id));
    }

    @Override
    public List<QuizAttempt> findByStudentId(long studentId) {
        return attemptsById.values().stream()
                .filter(a -> a.studentId() == studentId)
                .sorted(Comparator.comparing(QuizAttempt::submittedAt).thenComparingLong(QuizAttempt::id).reversed())
                .toList();
    }

    @Override
    public List<AttemptAnswer> findAnswersByAttemptId(long attemptId) {
        return answersByAttemptId.getOrDefault(attemptId, List.of());
    }

    @Override
    public List<AttemptSummary> findSummariesByStudentId(long studentId) {
        return findByStudentId(studentId).stream()
                .map(a -> new AttemptSummary(a, (int) findAnswersByAttemptId(a.id()).stream()
                        .filter(ans -> ans.selectedOptionId() != null).count()))
                .toList();
    }

    @Override
    public synchronized Created createWithAnswers(long studentId, long quizId, int totalQuestions, int correctCount,
                                                  BigDecimal scorePercent, List<NewAnswer> answers) {
        long attemptId = attemptIds.incrementAndGet();
        if (failNextWrite != null) {
            RuntimeException toThrow = failNextWrite;
            failNextWrite = null;
            // Nothing is stored: mirrors a rolled-back transaction.
            throw toThrow;
        }
        Set<Long> seen = new HashSet<>();
        for (NewAnswer a : answers) {
            if (!seen.add(a.questionId())) { // mirrors uq_answers_attempt_question
                throw new DuplicateResourceException("This attempt already has an answer for that question");
            }
        }
        QuizAttempt attempt = new QuizAttempt(attemptId, studentId, quizId, totalQuestions, correctCount,
                scorePercent, Instant.now().truncatedTo(ChronoUnit.SECONDS));
        List<AttemptAnswer> saved = new ArrayList<>();
        for (NewAnswer a : answers) {
            saved.add(new AttemptAnswer(answerIds.incrementAndGet(), attemptId, a.questionId(),
                    a.selectedOptionId(), a.correct()));
        }
        attemptsById.put(attemptId, attempt);
        answersByAttemptId.put(attemptId, saved);
        return new Created(attempt, saved);
    }

    public void failNextWrite(RuntimeException toThrow) {
        this.failNextWrite = toThrow;
    }

    public int attemptCount() {
        return attemptsById.size();
    }

    public int answerCount() {
        return answersByAttemptId.values().stream().mapToInt(List::size).sum();
    }
}
