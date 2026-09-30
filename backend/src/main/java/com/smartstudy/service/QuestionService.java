package com.smartstudy.service;

import com.smartstudy.dto.CreateQuestionRequest;
import com.smartstudy.dto.QuestionAdminResponse;
import com.smartstudy.dto.QuestionResponse;
import com.smartstudy.model.Question;
import com.smartstudy.model.QuestionOption;
import com.smartstudy.repository.QuestionRepository;
import com.smartstudy.repository.QuizRepository;
import com.smartstudy.repository.TopicRepository;
import com.smartstudy.util.NotFoundException;
import com.smartstudy.util.ValidationException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Listing questions for a quiz (quiz-taking representation, never the answer key) and creating a
 * question with its options as one atomic operation (administrative representation).
 */
public class QuestionService {

    static final int TEXT_MAX = 1000;
    static final int OPTION_TEXT_MAX = 500;
    static final int MIN_OPTIONS = 2;
    static final int MAX_OPTIONS = 6;

    private final QuestionRepository questions;
    private final QuizRepository quizzes;
    private final TopicRepository topics;

    public QuestionService(QuestionRepository questions, QuizRepository quizzes, TopicRepository topics) {
        this.questions = questions;
        this.quizzes = quizzes;
        this.topics = topics;
    }

    /** Questions and options for a quiz, safe for quiz-taking: never includes which option is correct. */
    public List<QuestionResponse> listForQuiz(long quizId) {
        requireQuiz(quizId);
        List<Question> found = questions.findByQuizId(quizId);
        Map<Long, List<QuestionOption>> options =
                questions.optionsByQuestionIds(found.stream().map(Question::id).toList());
        return found.stream().map(q -> toResponse(q, options.getOrDefault(q.id(), List.of()))).toList();
    }

    /** Creates a question and its options atomically, returning the administrative representation. */
    public QuestionAdminResponse create(long quizId, CreateQuestionRequest request) {
        requireQuiz(quizId);

        String text = request.questionText() == null ? null : request.questionText().trim();
        List<CreateQuestionRequest.OptionInput> rawOptions = request.options();

        Map<String, String> errors = new LinkedHashMap<>();
        validateText(text, errors);
        validateTopic(request.topicId(), errors);
        validateOptions(rawOptions, errors);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        List<QuestionRepository.NewOption> toInsert = rawOptions.stream()
                .map(o -> new QuestionRepository.NewOption(o.text().trim(), Boolean.TRUE.equals(o.correct())))
                .toList();
        QuestionRepository.Created created = questions.createWithOptions(quizId, request.topicId(), text, toInsert);
        return toAdminResponse(created);
    }

    private void requireQuiz(long quizId) {
        if (quizzes.findById(quizId).isEmpty()) {
            throw new NotFoundException("Quiz not found");
        }
    }

    private static void validateText(String text, Map<String, String> errors) {
        if (text == null || text.isEmpty()) {
            errors.put("questionText", "Question text is required");
        } else if (text.length() > TEXT_MAX) {
            errors.put("questionText", "Question text must be at most " + TEXT_MAX + " characters");
        } else if (text.codePoints().anyMatch(Character::isISOControl)) {
            errors.put("questionText", "Question text contains invalid characters");
        }
    }

    private void validateTopic(Long topicId, Map<String, String> errors) {
        if (topicId == null || topicId <= 0) {
            errors.put("topicId", "Topic is required");
        } else if (topics.findById(topicId).isEmpty()) {
            errors.put("topicId", "Topic does not exist");
        }
    }

    private static void validateOptions(List<CreateQuestionRequest.OptionInput> options, Map<String, String> errors) {
        if (options == null || options.size() < MIN_OPTIONS || options.size() > MAX_OPTIONS) {
            errors.put("options", "Provide between " + MIN_OPTIONS + " and " + MAX_OPTIONS + " options");
            return;
        }
        boolean anyOptionTextError = false;
        int correctCount = 0;
        for (int i = 0; i < options.size(); i++) {
            CreateQuestionRequest.OptionInput option = options.get(i);
            if (option == null) {
                // JSON "options": [null, ...] - malformed input, not a server error.
                errors.put("options[" + i + "]", "Option is required");
                anyOptionTextError = true;
                continue;
            }
            String text = option.text() == null ? null : option.text().trim();
            if (text == null || text.isEmpty()) {
                errors.put("options[" + i + "].text", "Option text is required");
                anyOptionTextError = true;
            } else if (text.length() > OPTION_TEXT_MAX) {
                errors.put("options[" + i + "].text", "Option text must be at most " + OPTION_TEXT_MAX + " characters");
                anyOptionTextError = true;
            } else if (text.codePoints().anyMatch(Character::isISOControl)) {
                errors.put("options[" + i + "].text", "Option text contains invalid characters");
                anyOptionTextError = true;
            }
            if (Boolean.TRUE.equals(option.correct())) {
                correctCount++;
            }
        }
        if (!anyOptionTextError && correctCount != 1) {
            errors.put("options", "Exactly one option must be marked correct");
        }
    }

    private static QuestionResponse toResponse(Question q, List<QuestionOption> options) {
        List<QuestionResponse.Option> mapped = options.stream()
                .map(o -> new QuestionResponse.Option(o.id(), o.optionText())).toList();
        return new QuestionResponse(q.id(), q.questionText(), q.position(), mapped);
    }

    private static QuestionAdminResponse toAdminResponse(QuestionRepository.Created created) {
        Question q = created.question();
        List<QuestionAdminResponse.Option> mapped = created.options().stream()
                .map(o -> new QuestionAdminResponse.Option(o.id(), o.optionText(), o.correct())).toList();
        return new QuestionAdminResponse(q.id(), q.quizId(), q.topicId(), q.questionText(), q.position(), mapped);
    }
}
