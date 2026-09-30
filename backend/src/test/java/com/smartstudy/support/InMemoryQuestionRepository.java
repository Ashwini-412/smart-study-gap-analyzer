package com.smartstudy.support;

import com.smartstudy.model.Question;
import com.smartstudy.model.QuestionOption;
import com.smartstudy.repository.QuestionRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryQuestionRepository implements QuestionRepository {

    private final Map<Long, Question> questionsById = new ConcurrentHashMap<>();
    private final Map<Long, List<QuestionOption>> optionsByQuestionId = new ConcurrentHashMap<>();
    private final AtomicLong questionIds = new AtomicLong();
    private final AtomicLong optionIds = new AtomicLong();

    /** Set by a test to make the next createWithOptions throw after inserting the question, to prove rollback. */
    private volatile RuntimeException failOptionInsert;

    @Override
    public List<Question> findByQuizId(long quizId) {
        return questionsById.values().stream()
                .filter(q -> q.quizId() == quizId)
                .sorted(Comparator.comparingInt(Question::position))
                .toList();
    }

    @Override
    public Map<Long, List<QuestionOption>> optionsByQuestionIds(Collection<Long> questionIds) {
        Map<Long, List<QuestionOption>> result = new LinkedHashMap<>();
        for (Long id : questionIds) {
            List<QuestionOption> options = optionsByQuestionId.get(id);
            if (options != null) {
                result.put(id, options);
            }
        }
        return result;
    }

    @Override
    public synchronized Created createWithOptions(long quizId, long topicId, String questionText,
                                                   List<NewOption> options) {
        int position = (int) findByQuizId(quizId).stream().count() + 1;
        long questionId = questionIds.incrementAndGet();
        Question question = new Question(questionId, quizId, topicId, questionText, position);

        if (failOptionInsert != null) {
            RuntimeException toThrow = failOptionInsert;
            failOptionInsert = null;
            // Nothing is stored: mirrors a rolled-back transaction (question insert not committed either).
            throw toThrow;
        }

        List<QuestionOption> saved = new ArrayList<>();
        for (NewOption option : options) {
            saved.add(new QuestionOption(optionIds.incrementAndGet(), questionId, option.text(), option.correct()));
        }
        questionsById.put(questionId, question);
        optionsByQuestionId.put(questionId, saved);
        return new Created(question, saved);
    }

    /** Makes the next createWithOptions call fail as if option insertion failed mid-transaction. */
    public void failNextOptionInsert(RuntimeException toThrow) {
        this.failOptionInsert = toThrow;
    }

    public int questionCount() {
        return questionsById.size();
    }

    public int optionCount() {
        return optionsByQuestionId.values().stream().mapToInt(List::size).sum();
    }
}
