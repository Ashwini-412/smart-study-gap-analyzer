package com.smartstudy.controller;

import com.smartstudy.dto.HealthResponse;
import com.smartstudy.util.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/** GET /api/health - liveness check. Does not touch the database. */
public class HealthController implements HttpHandler {

    public static final String PATH = "/api/health";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            // HttpServer matches contexts by prefix, so reject /api/health/anything.
            if (!PATH.equals(exchange.getRequestURI().getPath())) {
                HttpUtil.sendError(exchange, 404, "Not found");
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                HttpUtil.sendError(exchange, 405, "Method not allowed");
                return;
            }
            HttpUtil.sendJson(exchange, 200, new HealthResponse("ok"));
        }
    }
}
