package com.smartstudy.controller;

import com.smartstudy.service.AttemptService;
import com.smartstudy.service.AuthService;
import com.smartstudy.util.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;

/**
 * GET /api/attempts/{id}: the stored result of one of the caller's own attempts. Mounted on the
 * "/api/attempts/" prefix (HttpServer contexts cannot match a path parameter) behind AuthFilter;
 * the owner is taken from the session only, and ownership is enforced in AttemptService.
 */
public class AttemptController {

    private static final String PREFIX = "/api/attempts/";

    private final AttemptService attempts;
    private final AuthService authService;

    public AttemptController(AttemptService attempts, AuthService authService) {
        this.attempts = attempts;
        this.authService = authService;
    }

    public void mount(HttpServer server) {
        server.createContext(PREFIX, (HttpHandler) this::handle).getFilters().add(new AuthFilter(authService));
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String idText = path.substring(PREFIX.length());
            Long id = idText.contains("/") ? null : QuizIdRouter.parseId(idText);
            if (id == null) {
                HttpUtil.sendError(exchange, 404, "Not found");
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                HttpUtil.sendError(exchange, 405, "Method not allowed");
                return;
            }
            Endpoint.run(exchange, "GET", path, ex -> {
                long studentId = AuthFilter.currentUser(ex).studentId();
                HttpUtil.sendJson(ex, 200, attempts.getResult(studentId, id));
            });
        }
    }
}
