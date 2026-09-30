package com.smartstudy.service;

import com.smartstudy.model.Question;
import com.smartstudy.model.QuestionOption;
import com.smartstudy.repository.QuestionRepository;
import com.smartstudy.repository.QuizRepository;
import com.smartstudy.util.NotFoundException;
import com.smartstudy.util.ValidationException;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Business-rule validation for quiz submissions: the service-layer foundation a later
 * quiz-taking milestone builds on. This deliberately does not grade anything (no comparison
 * against {@code question_options.is_correct}, no score calculation) and does not persist
 * anything - persistence is {@code QuizAttemptRepository} (Milestone 5), and grading/scoring
 * belongs to the milestone that implements evaluation. Nothing here depends on
 * {@code QuizAttemptRepository}: none of the rules below read or write quiz_attempts/
 * attempt_answers, so introducing that dependency now would be speculative.
 *
 * <p>{@code studentId} in the eventual submission flow must always be the id the caller resolved
 * from the authenticated session (e.g. {@code AuthenticatedUser.studentId()}), never a value read
 * from the request body - mirroring how every other service in this project resolves identity
 * (see {@code AuthFilter}/{@code AuthenticatedUser}). This class has no parameter through which a
 * caller could pass a claimed identity or a claimed score, so neither can leak in by accident.
 */
public class AttemptService {

    private final QuizRepository quizzes;
    private final QuestionRepository questions;

    public AttemptService(QuizRepository quizzes, QuestionRepository questions) {
        this.quizzes = quizzes;
        this.questions = questions;
    }

    /** One submitted answer: the question being answered, and the option chosen (null = left unanswered). */
    public record AnswerSubmission(Long questionId, Long selectedOptionId) {
    }

    /**
     * Validates a proposed submission against the quiz's actual structure:
     * <ul>
     *   <li>the quiz must exist;</li>
     *   <li>every answered question must belong to that quiz;</li>
     *   <li>every selected option must belong to the question it answers;</li>
     *   <li>no question may be answered more than once in the same submission.</li>
     * </ul>
     * Throws {@link NotFoundException} if the quiz does not exist, or {@link ValidationException}
     * (with one field error per violation) otherwise. Never grades or persists anything.
     */
    public void validateSubmission(long quizId, List<AnswerSubmission> answers) {
        if (quizzes.findById(quizId).isEmpty()) {
            throw new NotFoundException("Quiz not found");
        }
        if (answers == null || answers.isEmpty()) {
            throw new ValidationException(Map.of("answers", "At least one answer is required"));
        }

        Set<Long> validQuestionIds = new HashSet<>();
        for (Question q : questions.findByQuizId(quizId)) {
            validQuestionIds.add(q.id());
        }

        Set<Long> questionIdsInSubmission = new HashSet<>();
        for (AnswerSubmission a : answers) {
            if (a.questionId() != null) {
                questionIdsInSubmission.add(a.questionId());
            }
        }
        Map<Long, List<QuestionOption>> optionsByQuestion = questions.optionsByQuestionIds(questionIdsInSubmission);

        Map<String, String> errors = new LinkedHashMap<>();
        Set<Long> seenQuestionIds = new HashSet<>();
        for (int i = 0; i < answers.size(); i++) {
            AnswerSubmission a = answers.get(i);
            String prefix = "answers[" + i + "]";
            if (a.questionId() == null) {
                errors.put(prefix + ".questionId", "Question id is required");
                continue;
            }
            if (!validQuestionIds.contains(a.questionId())) {
                errors.put(prefix + ".questionId", "Question does not belong to this quiz");
                continue;
            }
            if (!seenQuestionIds.add(a.questionId())) {
                errors.put(prefix + ".questionId", "This question was answered more than once");
                continue;
            }
            if (a.selectedOptionId() != null) {
                List<QuestionOption> options = optionsByQuestion.getOrDefault(a.questionId(), List.of());
                boolean belongsToQuestion = options.stream().anyMatch(o -> o.id() == a.selectedOptionId());
                if (!belongsToQuestion) {
                    errors.put(prefix + ".selectedOptionId", "Option does not belong to this question");
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }
}
