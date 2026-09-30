package com.smartstudy.controller;

import com.smartstudy.dto.ValidationErrorResponse;
import com.smartstudy.util.AuthenticationException;
import com.smartstudy.util.BadRequestException;
import com.smartstudy.util.DuplicateEmailException;
import com.smartstudy.util.ErrorLog;
import com.smartstudy.util.HttpUtil;
import com.smartstudy.util.ValidationException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * One exact path + one HTTP method. Rejects everything else (404 / 405) and turns the
 * application's exceptions into JSON error responses, so controller methods stay thin.
 */
final class Endpoint implements HttpHandler {

    @FunctionalInterface
    interface Action {
        void handle(HttpExchange exchange) throws Exception;
    }

    private final String path;
    private final String method;
    private final Action action;

    Endpoint(String path, String method, Action action) {
        this.path = path;
        this.method = method;
        this.action = action;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            // HttpServer matches contexts by prefix; require the exact path.
            if (!path.equals(exchange.getRequestURI().getPath())) {
                HttpUtil.sendError(exchange, 404, "Not found");
                return;
            }
            if (!method.equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", method);
                HttpUtil.sendError(exchange, 405, "Method not allowed");
                return;
            }
            try {
                action.handle(exchange);
            } catch (ValidationException e) {
                HttpUtil.sendJson(exchange, 400, new ValidationErrorResponse(e.getMessage(), e.fieldErrors()));
            } catch (BadRequestException e) {
                HttpUtil.sendError(exchange, 400, e.getMessage());
            } catch (AuthenticationException e) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                HttpUtil.sendError(exchange, 401, e.getMessage());
            } catch (DuplicateEmailException e) {
                HttpUtil.sendError(exchange, 409, e.getMessage());
            } catch (Exception e) {
                // Detail goes to the server log only; the client gets a generic message.
                System.err.println("Unhandled error on " + method + " " + path + ": " + ErrorLog.describe(e));
                HttpUtil.sendError(exchange, 500, "Internal server error");
            }
        }
    }
}
