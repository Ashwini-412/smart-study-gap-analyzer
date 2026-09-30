package com.smartstudy.controller;

import com.smartstudy.dto.CreateQuestionRequest;
import com.smartstudy.dto.SubmitAttemptRequest;
import com.smartstudy.service.AttemptService;
import com.smartstudy.service.QuestionService;
import com.smartstudy.service.QuizService;
import com.smartstudy.util.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles GET /api/quizzes/{id}, GET/POST /api/quizzes/{id}/questions and
 * POST /api/quizzes/{id}/attempts. HttpServer contexts only match fixed path prefixes, so this is
 * mounted on the "/api/quizzes/" prefix and parses the id segment itself, then reuses
 * {@link Endpoint#run} so exceptions map to HTTP statuses exactly the same way as every other
 * route in the app.
 */
final class QuizIdRouter implements HttpHandler {

    private static final String PREFIX = "/api/quizzes/";
    private static final String QUESTIONS_SEGMENT = "questions";
    private static final String ATTEMPTS_SEGMENT = "attempts";

    private final QuizService quizzes;
    private final QuestionService questions;
    private final AttemptService attempts;

    QuizIdRouter(QuizService quizzes, QuestionService questions, AttemptService attempts) {
        this.quizzes = quizzes;
        this.questions = questions;
        this.attempts = attempts;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String remainder = path.substring(PREFIX.length());
            String[] parts = remainder.isEmpty() ? new String[0] : remainder.split("/", -1);

            if (parts.length == 1 && !parts[0].isEmpty()) {
                handleQuiz(exchange, parts[0], path);
            } else if (parts.length == 2 && !parts[0].isEmpty() && QUESTIONS_SEGMENT.equals(parts[1])) {
                handleQuestions(exchange, parts[0], path);
            } else if (parts.length == 2 && !parts[0].isEmpty() && ATTEMPTS_SEGMENT.equals(parts[1])) {
                handleAttempts(exchange, parts[0], path);
            } else {
                HttpUtil.sendError(exchange, 404, "Not found");
            }
        }
    }

    private void handleQuiz(HttpExchange exchange, String idText, String path) throws IOException {
        Long id = parseId(idText);
        if (id == null) {
            HttpUtil.sendError(exchange, 404, "Not found");
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            HttpUtil.sendError(exchange, 405, "Method not allowed");
            return;
        }
        Endpoint.run(exchange, "GET", path, ex -> HttpUtil.sendJson(ex, 200, quizzes.getById(id)));
    }

    private void handleQuestions(HttpExchange exchange, String idText, String path) throws IOException {
        Long id = parseId(idText);
        if (id == null) {
            HttpUtil.sendError(exchange, 404, "Not found");
            return;
        }
        String method = exchange.getRequestMethod();
        if ("GET".equals(method)) {
            Endpoint.run(exchange, method, path, ex -> HttpUtil.sendJson(ex, 200, questions.listForQuiz(id)));
        } else if ("POST".equals(method)) {
            Endpoint.run(exchange, method, path, ex -> {
                CreateQuestionRequest request = HttpUtil.readJson(ex, CreateQuestionRequest.class);
                HttpUtil.sendJson(ex, 201, questions.create(id, request));
            });
        } else {
            exchange.getResponseHeaders().set("Allow", "GET, POST");
            HttpUtil.sendError(exchange, 405, "Method not allowed");
        }
    }

    private void handleAttempts(HttpExchange exchange, String idText, String path) throws IOException {
        Long id = parseId(idText);
        if (id == null) {
            HttpUtil.sendError(exchange, 404, "Not found");
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            HttpUtil.sendError(exchange, 405, "Method not allowed");
            return;
        }
        Endpoint.run(exchange, "POST", path, ex -> {
            SubmitAttemptRequest request = HttpUtil.readJson(ex, SubmitAttemptRequest.class);
            // Identity comes only from the session published by AuthFilter, never from the request.
            long studentId = AuthFilter.currentUser(ex).studentId();
            HttpUtil.sendJson(ex, 201, attempts.submit(studentId, id, toSubmissions(request.answers())));
        });
    }

    /** Maps the request DTO to the service type, keeping null elements so the service can report them. */
    private static List<AttemptService.AnswerSubmission> toSubmissions(List<SubmitAttemptRequest.AnswerInput> answers) {
        if (answers == null) {
            return null;
        }
        List<AttemptService.AnswerSubmission> out = new ArrayList<>(answers.size());
        for (SubmitAttemptRequest.AnswerInput a : answers) {
            out.add(a == null ? null : new AttemptService.AnswerSubmission(a.questionId(), a.selectedOptionId()));
        }
        return out;
    }

    /** A positive long, or null if {@code text} is not one (never a valid resource id). Shared with AttemptController. */
    static Long parseId(String text) {
        try {
            long id = Long.parseLong(text);
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
