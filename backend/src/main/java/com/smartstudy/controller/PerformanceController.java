package com.smartstudy.controller;

import com.smartstudy.service.AuthService;
import com.smartstudy.service.PerformanceService;
import com.smartstudy.util.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;

/** GET /api/performance/gaps: the caller's topic-level study gaps. Requires a valid session. */
public class PerformanceController {

    private static final String GAPS_PATH = "/api/performance/gaps";

    private final PerformanceService performance;
    private final AuthService authService;

    public PerformanceController(PerformanceService performance, AuthService authService) {
        this.performance = performance;
        this.authService = authService;
    }

    public void mount(HttpServer server) {
        server.createContext(GAPS_PATH, new Endpoint(GAPS_PATH, "GET", this::gaps))
                .getFilters().add(new AuthFilter(authService));
    }

    private void gaps(HttpExchange exchange) throws IOException {
        // Identity comes only from the session published by AuthFilter.
        HttpUtil.sendJson(exchange, 200, performance.gaps(AuthFilter.currentUser(exchange).studentId()));
    }
}
