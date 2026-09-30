package com.smartstudy.controller;

import com.smartstudy.dto.CreateTopicRequest;
import com.smartstudy.service.AuthService;
import com.smartstudy.service.TopicService;
import com.smartstudy.util.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.util.Map;

/** /api/topics: list (GET) and create (POST). Both require a valid session. */
public class TopicController {

    private static final String PATH = "/api/topics";

    private final TopicService service;
    private final AuthService authService;

    public TopicController(TopicService service, AuthService authService) {
        this.service = service;
        this.authService = authService;
    }

    public void mount(HttpServer server) {
        server.createContext(PATH, new Endpoint(PATH, Map.of("GET", this::list, "POST", this::create)))
                .getFilters().add(new AuthFilter(authService));
    }

    private void list(HttpExchange exchange) throws IOException {
        HttpUtil.sendJson(exchange, 200, service.list());
    }

    private void create(HttpExchange exchange) throws IOException {
        CreateTopicRequest request = HttpUtil.readJson(exchange, CreateTopicRequest.class);
        HttpUtil.sendJson(exchange, 201, service.create(request));
    }
}
