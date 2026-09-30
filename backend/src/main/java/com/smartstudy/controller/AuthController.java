package com.smartstudy.controller;

import com.smartstudy.dto.AuthenticatedUser;
import com.smartstudy.dto.LoginRequest;
import com.smartstudy.dto.MessageResponse;
import com.smartstudy.dto.RegisterRequest;
import com.smartstudy.dto.StudentResponse;
import com.smartstudy.service.AuthService;
import com.smartstudy.util.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;

/** /api/auth/*: register, login (public); me, logout (require a valid session). */
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    public void mount(HttpServer server) {
        AuthFilter filter = new AuthFilter(service);
        server.createContext("/api/auth/register", new Endpoint("/api/auth/register", "POST", this::register));
        server.createContext("/api/auth/login", new Endpoint("/api/auth/login", "POST", this::login));
        server.createContext("/api/auth/me", new Endpoint("/api/auth/me", "GET", this::me))
                .getFilters().add(filter);
        server.createContext("/api/auth/logout", new Endpoint("/api/auth/logout", "POST", this::logout))
                .getFilters().add(filter);
    }

    private void register(HttpExchange exchange) throws IOException {
        RegisterRequest request = HttpUtil.readJson(exchange, RegisterRequest.class);
        HttpUtil.sendJson(exchange, 201, service.register(request));
    }

    private void login(HttpExchange exchange) throws IOException {
        LoginRequest request = HttpUtil.readJson(exchange, LoginRequest.class);
        HttpUtil.sendJson(exchange, 200, service.login(request));
    }

    private void me(HttpExchange exchange) throws IOException {
        AuthenticatedUser user = AuthFilter.currentUser(exchange);
        HttpUtil.sendJson(exchange, 200, new StudentResponse(user.studentId(), user.name(), user.email()));
    }

    private void logout(HttpExchange exchange) throws IOException {
        service.logout(AuthFilter.currentUser(exchange).sessionId());
        HttpUtil.sendJson(exchange, 200, new MessageResponse("Logged out"));
    }
}
