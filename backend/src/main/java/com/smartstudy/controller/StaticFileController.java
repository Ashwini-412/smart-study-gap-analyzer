package com.smartstudy.controller;

import com.smartstudy.util.HttpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Serves the vanilla frontend from one directory at "/" (same origin as the API, so no CORS).
 * Mounted on the root context, which HttpServer only uses when no longer /api/... context
 * matches. Only GET/HEAD, only regular files of known types inside the root (no traversal, no
 * symlink escapes, no hidden files, no directory listings). Every response carries a CSP that
 * allows scripts/styles from this origin only, so injected inline script cannot run.
 */
public class StaticFileController implements HttpHandler {

    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; "
            + "form-action 'self'; frame-ancestors 'none'";

    private static final Map<String, String> TYPES = Map.of(
            ".html", "text/html; charset=utf-8",
            ".css", "text/css; charset=utf-8",
            ".js", "text/javascript; charset=utf-8",
            ".svg", "image/svg+xml",
            ".png", "image/png",
            ".ico", "image/x-icon");

    private final Path root;

    /** {@code root} must be an existing directory. */
    public StaticFileController(Path root) throws IOException {
        this.root = root.toRealPath();
    }

    public void mount(HttpServer server) {
        server.createContext("/", this);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api") || path.startsWith("/api/")) {
                // An API path no controller claimed: keep the API's JSON error format.
                HttpUtil.sendError(exchange, 404, "Not found");
                return;
            }
            String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                HttpUtil.sendError(exchange, 405, "Method not allowed");
                return;
            }

            Path file = resolve(path);
            String type = file == null ? null : TYPES.get(extension(file));
            if (type == null) {
                sendText(exchange, 404, "Not found", "HEAD".equals(method));
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            securityHeaders(exchange);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            if ("HEAD".equals(method)) {
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(bytes.length));
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /** The regular file inside root for this URL path, or null. "/" and ".../" mean index.html. */
    private Path resolve(String urlPath) {
        String relative = urlPath.endsWith("/") ? urlPath + "index.html" : urlPath;
        for (String segment : relative.split("/")) {
            if (segment.startsWith(".")) {
                return null; // hidden files, "." and ".." segments
            }
        }
        try {
            Path candidate = root.resolve(relative.substring(1)).normalize();
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
                return null;
            }
            Path real = candidate.toRealPath(); // a symlink must not lead outside root
            return real.startsWith(root) ? real : null;
        } catch (InvalidPathException | IOException e) {
            return null;
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    private static void securityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
    }

    private static void sendText(HttpExchange exchange, int status, String text, boolean head) throws IOException {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        securityHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        if (head) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
