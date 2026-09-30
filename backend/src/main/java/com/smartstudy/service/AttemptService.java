package com.smartstudy.service;

import com.smartstudy.dto.AttemptHistoryItem;
import com.smartstudy.dto.AttemptResultResponse;
import com.smartstudy.dto.AttemptSubmissionResponse;
import com.smartstudy.model.AttemptAnswer;
import com.smartstudy.model.Question;
import com.smartstudy.model.QuestionOption;
import com.smartstudy.model.QuizAttempt;
import com.smartstudy.repository.QuestionRepository;
import com.smartstudy.repository.QuizAttemptRepository;
import com.smartstudy.repository.QuizRepository;
import com.smartstudy.util.NotFoundException;
import com.smartstudy.util.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Quiz submissions: validation against the quiz's real structure, server-side evaluation, and
 * atomic persistence of the attempt with its answers.
 *
 * <p>Evaluation happens here rather than in a later milestone because the schema requires it at
 * insert time: quiz_attempts.correct_count / score_percent and attempt_answers.is_correct are all
 * NOT NULL. Every value is derived from server data ({@code question_options.is_correct}); nothing
 * about correctness or score is ever read from the client.
 *
 * <p>{@code studentId} passed to {@link #submit} must be the id the caller resolved from the
 * authenticated session ({@code AuthenticatedUser.studentId()}), never a value from the request.
 */
public class AttemptService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final QuizRepository quizzes;
    private final QuestionRepository questions;
    private final QuizAttemptRepository attempts;

    public AttemptService(QuizRepository quizzes, QuestionRepository questions, QuizAttemptRepository attempts) {
        this.quizzes = quizzes;
        this.questions = questions;
        this.attempts = attempts;
    }

    /** One submitted answer: the question being answered, and the option chosen (null = left unanswered). */
    public record AnswerSubmission(Long questionId, Long selectedOptionId) {
    }

    /** The quiz's questions (position order) and each question's options, loaded once per request. */
    private record QuizStructure(List<Question> questions, Map<Long, List<QuestionOption>> options) {
    }

    /**
     * Validates a proposed submission against the quiz's actual structure:
     * <ul>
     *   <li>the quiz must exist;</li>
     *   <li>at least one answer must be supplied, and no answer may be null;</li>
     *   <li>every answered question must belong to that quiz;</li>
     *   <li>every selected option must belong to the question it answers;</li>
     *   <li>no question may be answered more than once in the same submission.</li>
     * </ul>
     * Throws {@link NotFoundException} if the quiz does not exist, or {@link ValidationException}
     * (with one field error per violation) otherwise. Does not evaluate or persist anything.
     */
    public void validateSubmission(long quizId, List<AnswerSubmission> answers) {
        loadAndValidate(quizId, answers);
    }

    /**
     * Validates, evaluates and stores a submission for the authenticated student. Every question in
     * the quiz gets an answer row; questions the student did not answer are stored with no selected
     * option and count as incorrect (docs/ARCHITECTURE.md section 7). The attempt and all of its
     * answers are written in one transaction by the repository.
     */
    public AttemptSubmissionResponse submit(long studentId, long quizId, List<AnswerSubmission> answers) {
        QuizStructure quiz = loadAndValidate(quizId, answers);

        Map<Long, Long> selectedByQuestion = new HashMap<>(); // null values allowed: explicitly unanswered
        for (AnswerSubmission a : answers) {
            selectedByQuestion.put(a.questionId(), a.selectedOptionId());
        }

        List<QuizAttemptRepository.NewAnswer> rows = new ArrayList<>();
        int correctCount = 0;
        int answeredCount = 0;
        for (Question q : quiz.questions()) {
            Long selected = selectedByQuestion.get(q.id());
            boolean correct = false;
            if (selected != null) {
                answeredCount++;
                correct = quiz.options().getOrDefault(q.id(), List.of()).stream()
                        .anyMatch(o -> o.id() == selected && o.correct());
            }
            if (correct) {
                correctCount++;
            }
            rows.add(new QuizAttemptRepository.NewAnswer(q.id(), selected, correct));
        }

        // Validation guarantees at least one answer to a question of this quiz, so total > 0.
        int totalQuestions = quiz.questions().size();
        BigDecimal scorePercent = BigDecimal.valueOf(correctCount).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(totalQuestions), 2, RoundingMode.HALF_UP);

        QuizAttemptRepository.Created created =
                attempts.createWithAnswers(studentId, quizId, totalQuestions, correctCount, scorePercent, rows);
        return new AttemptSubmissionResponse(created.attempt().id(), quizId,
                created.attempt().submittedAt() == null ? null : created.attempt().submittedAt().toString(),
                totalQuestions, answeredCount);
    }

    /**
     * The stored evaluation of one attempt, for its owner only. Nothing is re-scored: every value
     * comes from what {@link #submit} persisted (answeredCount is derived from the stored answer
     * rows). An attempt that does not exist and one that belongs to another student are both
     * reported as {@link NotFoundException}, so a caller cannot probe for other students' attempt
     * ids. Two queries regardless of question count.
     */
    public AttemptResultResponse getResult(long studentId, long attemptId) {
        QuizAttempt attempt = attempts.findById(attemptId)
                .filter(a -> a.studentId() == studentId)
                .orElseThrow(() -> new NotFoundException("Attempt not found"));

        List<AttemptResultResponse.Answer> answers = new ArrayList<>();
        int answeredCount = 0;
        for (AttemptAnswer a : attempts.findAnswersByAttemptId(attemptId)) {
            if (a.selectedOptionId() != null) {
                answeredCount++;
            }
            answers.add(new AttemptResultResponse.Answer(a.questionId(), a.selectedOptionId(), a.correct()));
        }
        return new AttemptResultResponse(attempt.id(), attempt.quizId(),
                attempt.submittedAt() == null ? null : attempt.submittedAt().toString(),
                attempt.totalQuestions(), answeredCount, attempt.correctCount(), attempt.scorePercent(), answers);
    }

    /**
     * The student's attempt history, most recent first. Scoped to {@code studentId} in the query
     * itself, so other students' attempts are never loaded. Every value is the snapshot stored at
     * submission; nothing is re-scored against the quiz's current questions. Empty list if the
     * student has no attempts.
     */
    public List<AttemptHistoryItem> history(long studentId) {
        return attempts.findSummariesByStudentId(studentId).stream()
                .map(s -> {
                    QuizAttempt a = s.attempt();
                    return new AttemptHistoryItem(a.id(), a.quizId(),
                            a.submittedAt() == null ? null : a.submittedAt().toString(), a.totalQuestions(),
                            s.answeredCount(), a.correctCount(), a.scorePercent());
                })
                .toList();
    }

    private QuizStructure loadAndValidate(long quizId, List<AnswerSubmission> answers) {
        if (quizzes.findById(quizId).isEmpty()) {
            throw new NotFoundException("Quiz not found");
        }
        if (answers == null || answers.isEmpty()) {
            throw new ValidationException(Map.of("answers", "At least one answer is required"));
        }

        List<Question> quizQuestions = questions.findByQuizId(quizId);
        Set<Long> validQuestionIds = new HashSet<>();
        for (Question q : quizQuestions) {
            validQuestionIds.add(q.id());
        }
        Map<Long, List<QuestionOption>> optionsByQuestion = questions.optionsByQuestionIds(validQuestionIds);

        Map<String, String> errors = new LinkedHashMap<>();
        Set<Long> seenQuestionIds = new HashSet<>();
        for (int i = 0; i < answers.size(); i++) {
            AnswerSubmission a = answers.get(i);
            String prefix = "answers[" + i + "]";
            if (a == null) {
                // JSON "answers": [null, ...] - malformed input, not a server error.
                errors.put(prefix, "Answer is required");
                continue;
            }
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
        return new QuizStructure(quizQuestions, optionsByQuestion);
    }
}
