package com.smartstudy.controller;

import com.smartstudy.dto.AuthenticatedUser;
import com.smartstudy.service.AuthService;
import com.smartstudy.util.ErrorLog;
import com.smartstudy.util.HttpUtil;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guards protected endpoints. Requires "Authorization: Bearer &lt;token&gt;", validates it
 * against the server-side session, and publishes the caller on the exchange. Anything
 * else is answered with 401 and the request never reaches the handler.
 */
public class AuthFilter extends Filter {

    // Tokens are 43 URL-safe Base64 chars; this rejects junk without touching the database.
    private static final Pattern BEARER = Pattern.compile("^Bearer ([A-Za-z0-9_-]{16,128})$", Pattern.CASE_INSENSITIVE);
    static final String UNAUTHENTICATED = "Authentication required";

    private final AuthService authService;

    public AuthFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        Optional<AuthenticatedUser> user;
        try {
            user = extractBearerToken(exchange.getRequestHeaders().getFirst("Authorization"))
                    .flatMap(authService::authenticate);
        } catch (RuntimeException e) {
            System.err.println("Authentication check failed: " + ErrorLog.describe(e));
            try (exchange) {
                HttpUtil.sendError(exchange, 500, "Internal server error");
            }
            return;
        }
        if (user.isEmpty()) {
            try (exchange) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                HttpUtil.sendError(exchange, 401, UNAUTHENTICATED);
            }
            return;
        }
        exchange.setAttribute(AuthenticatedUser.ATTRIBUTE, user.get());
        chain.doFilter(exchange);
    }

    /** The caller published by this filter. Only call from handlers mounted behind it. */
    static AuthenticatedUser currentUser(HttpExchange exchange) {
        Object user = exchange.getAttribute(AuthenticatedUser.ATTRIBUTE);
        if (user instanceof AuthenticatedUser u) {
            return u;
        }
        throw new IllegalStateException("Endpoint is not protected by AuthFilter");
    }

    /** Extracts the token from a well-formed Bearer header; empty for anything else. */
    static Optional<String> extractBearerToken(String header) {
        if (header == null) {
            return Optional.empty();
        }
        Matcher m = BEARER.matcher(header);
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    @Override
    public String description() {
        return "Bearer-token session authentication";
    }
}
