package com.smartstudy.controller;

import com.smartstudy.dto.CreateQuestionRequest;
import com.smartstudy.service.QuestionService;
import com.smartstudy.service.QuizService;
import com.smartstudy.util.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * Handles GET /api/quizzes/{id} and GET/POST /api/quizzes/{id}/questions. HttpServer contexts
 * only match fixed path prefixes, so this is mounted on the "/api/quizzes/" prefix and parses
 * the id segment itself, then reuses {@link Endpoint#run} so exceptions map to HTTP statuses
 * exactly the same way as every other route in the app.
 */
final class QuizIdRouter implements HttpHandler {

    private static final String PREFIX = "/api/quizzes/";
    private static final String QUESTIONS_SEGMENT = "questions";

    private final QuizService quizzes;
    private final QuestionService questions;

    QuizIdRouter(QuizService quizzes, QuestionService questions) {
        this.quizzes = quizzes;
        this.questions = questions;
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

    /** A positive long, or null if {@code text} is not one (never a valid resource id). */
    private static Long parseId(String text) {
        try {
            long id = Long.parseLong(text);
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
