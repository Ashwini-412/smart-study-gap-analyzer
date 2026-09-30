package com.smartstudy.controller;

import com.smartstudy.dto.ValidationErrorResponse;
import com.smartstudy.util.AuthenticationException;
import com.smartstudy.util.BadRequestException;
import com.smartstudy.util.DuplicateEmailException;
import com.smartstudy.util.DuplicateResourceException;
import com.smartstudy.util.ErrorLog;
import com.smartstudy.util.HttpUtil;
import com.smartstudy.util.NotFoundException;
import com.smartstudy.util.ValidationException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;
import java.util.TreeSet;

/**
 * One exact path + one or more HTTP methods (one action each). Rejects everything else
 * (404 / 405) and turns the application's exceptions into JSON error responses, so controller
 * methods stay thin.
 */
final class Endpoint implements HttpHandler {

    @FunctionalInterface
    interface Action {
        void handle(HttpExchange exchange) throws Exception;
    }

    private final String path;
    private final Map<String, Action> actions;

    Endpoint(String path, String method, Action action) {
        this(path, Map.of(method, action));
    }

    Endpoint(String path, Map<String, Action> actions) {
        this.path = path;
        this.actions = Map.copyOf(actions);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            // HttpServer matches contexts by prefix; require the exact path.
            if (!path.equals(exchange.getRequestURI().getPath())) {
                HttpUtil.sendError(exchange, 404, "Not found");
                return;
            }
            Action action = actions.get(exchange.getRequestMethod());
            if (action == null) {
                exchange.getResponseHeaders().set("Allow", String.join(", ", new TreeSet<>(actions.keySet())));
                HttpUtil.sendError(exchange, 405, "Method not allowed");
                return;
            }
            run(exchange, exchange.getRequestMethod(), path, action);
        }
    }

    /**
     * Runs {@code action}, turning the application's exceptions into the matching JSON error
     * response. Shared with handlers that match their own path (e.g. one with an id segment),
     * so every route in the app maps exceptions to HTTP statuses the same way.
     */
    static void run(HttpExchange exchange, String method, String path, Action action) throws IOException {
        try {
            action.handle(exchange);
        } catch (ValidationException e) {
            HttpUtil.sendJson(exchange, 400, new ValidationErrorResponse(e.getMessage(), e.fieldErrors()));
        } catch (BadRequestException e) {
            HttpUtil.sendError(exchange, 400, e.getMessage());
        } catch (AuthenticationException e) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            HttpUtil.sendError(exchange, 401, e.getMessage());
        } catch (NotFoundException e) {
            HttpUtil.sendError(exchange, 404, e.getMessage());
        } catch (DuplicateEmailException | DuplicateResourceException e) {
            HttpUtil.sendError(exchange, 409, e.getMessage());
        } catch (Exception e) {
            // Detail goes to the server log only; the client gets a generic message.
            System.err.println("Unhandled error on " + method + " " + path + ": " + ErrorLog.describe(e));
            HttpUtil.sendError(exchange, 500, "Internal server error");
        }
    }
}
