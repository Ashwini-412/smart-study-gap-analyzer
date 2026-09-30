package com.smartstudy.controller;

import com.smartstudy.dto.CreateQuizRequest;
import com.smartstudy.service.AuthService;
import com.smartstudy.service.QuestionService;
import com.smartstudy.service.QuizService;
import com.smartstudy.util.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.util.Map;

/**
 * /api/quizzes: list (GET) and create (POST).
 * /api/quizzes/{id} and /api/quizzes/{id}/questions are handled by {@link QuizIdRouter}, mounted
 * on the "/api/quizzes/" prefix context since HttpServer contexts cannot themselves match a path
 * parameter. All routes require a valid session.
 */
public class QuizController {

    private static final String PATH = "/api/quizzes";

    private final QuizService quizzes;
    private final QuestionService questions;
    private final AuthService authService;

    public QuizController(QuizService quizzes, QuestionService questions, AuthService authService) {
        this.quizzes = quizzes;
        this.questions = questions;
        this.authService = authService;
    }

    public void mount(HttpServer server) {
        server.createContext(PATH, new Endpoint(PATH, Map.of("GET", this::list, "POST", this::create)))
                .getFilters().add(new AuthFilter(authService));
        server.createContext(PATH + "/", new QuizIdRouter(quizzes, questions))
                .getFilters().add(new AuthFilter(authService));
    }

    private void list(HttpExchange exchange) throws IOException {
        HttpUtil.sendJson(exchange, 200, quizzes.list());
    }

    private void create(HttpExchange exchange) throws IOException {
        CreateQuizRequest request = HttpUtil.readJson(exchange, CreateQuizRequest.class);
        HttpUtil.sendJson(exchange, 201, quizzes.create(request));
    }
}
