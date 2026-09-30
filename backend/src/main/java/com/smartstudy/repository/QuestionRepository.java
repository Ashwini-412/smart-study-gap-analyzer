package com.smartstudy.repository;

import com.smartstudy.model.Question;
import com.smartstudy.model.QuestionOption;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface QuestionRepository {

    /** Questions for one quiz, ordered by position. */
    List<Question> findByQuizId(long quizId);

    /**
     * Options for each of the given questions, keyed by question id, each list ordered by
     * option id (insertion order). A question with no options yet is omitted.
     */
    Map<Long, List<QuestionOption>> optionsByQuestionIds(Collection<Long> questionIds);

    /**
     * Creates a question and all of its options as one atomic operation: either every row is
     * written, or none is (rolled back). {@code position} is assigned server-side (append to
     * the end of the quiz), never accepted from the client.
     */
    Created createWithOptions(long quizId, long topicId, String questionText, List<NewOption> options);

    /** One option to insert: not yet a persisted row, so it has no id. */
    record NewOption(String text, boolean correct) {
    }

    /** The question and options exactly as persisted, including their generated ids. */
    record Created(Question question, List<QuestionOption> options) {
    }
}
