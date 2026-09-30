package com.smartstudy.service;

import com.smartstudy.dto.CreateQuizRequest;
import com.smartstudy.dto.QuizResponse;
import com.smartstudy.dto.TopicResponse;
import com.smartstudy.model.Quiz;
import com.smartstudy.model.Topic;
import com.smartstudy.repository.QuizRepository;
import com.smartstudy.util.NotFoundException;
import com.smartstudy.util.ValidationException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Quiz listing, lookup and creation. A quiz has no direct topic reference in the current schema
 * (topics attach only through questions, so a quiz can span several); see the M4 report for why
 * "validate referenced topic exists" from the milestone brief does not apply to quiz creation.
 */
public class QuizService {

    static final int TITLE_MAX = 200;
    static final int DESCRIPTION_MAX = 1000;

    private final QuizRepository quizzes;

    public QuizService(QuizRepository quizzes) {
        this.quizzes = quizzes;
    }

    public List<QuizResponse> list() {
        List<Quiz> all = quizzes.findAll();
        Map<Long, List<Topic>> topicsByQuiz = quizzes.topicsByQuizIds(all.stream().map(Quiz::id).toList());
        return all.stream().map(q -> toResponse(q, topicsByQuiz.getOrDefault(q.id(), List.of()))).toList();
    }

    public QuizResponse getById(long id) {
        Quiz quiz = quizzes.findById(id).orElseThrow(() -> new NotFoundException("Quiz not found"));
        List<Topic> topics = quizzes.topicsByQuizIds(List.of(id)).getOrDefault(id, List.of());
        return toResponse(quiz, topics);
    }

    public QuizResponse create(CreateQuizRequest request) {
        String title = request.title() == null ? null : request.title().trim();
        String description = normaliseDescription(request.description());

        Map<String, String> errors = new LinkedHashMap<>();
        if (title == null || title.isEmpty()) {
            errors.put("title", "Title is required");
        } else if (title.length() > TITLE_MAX) {
            errors.put("title", "Title must be at most " + TITLE_MAX + " characters");
        } else if (title.codePoints().anyMatch(Character::isISOControl)) {
            errors.put("title", "Title contains invalid characters");
        }
        if (description != null) {
            if (description.length() > DESCRIPTION_MAX) {
                errors.put("description", "Description must be at most " + DESCRIPTION_MAX + " characters");
            } else if (description.codePoints().anyMatch(Character::isISOControl)) {
                errors.put("description", "Description contains invalid characters");
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        return toResponse(quizzes.create(title, description), List.of());
    }

    private static String normaliseDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static QuizResponse toResponse(Quiz q, List<Topic> topics) {
        List<TopicResponse> topicResponses = topics.stream().map(t -> new TopicResponse(t.id(), t.name())).toList();
        return new QuizResponse(q.id(), q.title(), q.description(),
                q.createdAt() == null ? null : q.createdAt().toString(), topicResponses);
    }
}
