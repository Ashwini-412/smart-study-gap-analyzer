package com.smartstudy.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstudy.dto.ErrorResponse;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** JSON request/response helpers shared by controllers. */
public final class HttpUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_BODY_BYTES = 16 * 1024;

    private HttpUtil() {
    }

    public static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        // Responses can carry per-user data (and, at login, a session token): never cache.
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    public static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, new ErrorResponse(message));
    }

    /**
     * Parses the request body as JSON into {@code type}. Failures raise
     * {@link BadRequestException} with a fixed message: parser messages can quote
     * the offending input (which may be a password), so they are never passed on.
     */
    public static <T> T readJson(HttpExchange exchange, Class<T> type) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new BadRequestException("Request body too large");
        }
        try {
            T value = MAPPER.readValue(body, type);
            if (value == null) {
                throw new BadRequestException("Request body is required");
            }
            return value;
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Invalid JSON body");
        }
    }
}
