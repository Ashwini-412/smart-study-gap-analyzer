package com.smartstudy.controller;

import com.smartstudy.App;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static frontend serving at "/" alongside the API (health endpoint as the API sample). */
class StaticFileControllerTest {

    @TempDir
    static Path temp;

    private static HttpServer server;
    private static String base;
    private static int port;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws IOException {
        Path web = Files.createDirectories(temp.resolve("web"));
        Files.writeString(web.resolve("index.html"), "<!doctype html><title>home</title>");
        Files.createDirectories(web.resolve("css"));
        Files.writeString(web.resolve("css/styles.css"), "body{}");
        Files.createDirectories(web.resolve("js"));
        Files.writeString(web.resolve("js/app.js"), "export {};");
        Files.writeString(web.resolve(".secret.html"), "hidden");
        Files.writeString(web.resolve("notes.txt"), "not a served type");
        Files.createDirectories(web.resolve("empty-dir"));
        Files.writeString(temp.resolve("outside.html"), "outside the web root");
        Files.createSymbolicLink(web.resolve("escape.html"), temp.resolve("outside.html"));

        server = App.createServer("127.0.0.1", 0);
        App.mountFrontend(server, web);
        server.start();
        port = server.getAddress().getPort();
        base = "http://127.0.0.1:" + port;
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Sends a raw request line so the path reaches the server exactly as written (no client normalisation). */
    private static String rawStatusLine(String path) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            String response = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return response.lines().findFirst().orElse("");
        }
    }

    @Test
    void rootServesIndexHtmlWithSecurityHeaders() throws Exception {
        HttpResponse<String> r = send("GET", "/");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("<title>home</title>"));
        assertEquals("text/html; charset=utf-8", r.headers().firstValue("Content-Type").orElse(""));
        assertEquals(StaticFileController.CONTENT_SECURITY_POLICY,
                r.headers().firstValue("Content-Security-Policy").orElse(""));
        assertTrue(r.headers().firstValue("Content-Security-Policy").orElse("").contains("script-src 'self'"));
        assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals(200, send("GET", "/index.html").statusCode());
    }

    @Test
    void cssAndJsAreServedWithTheirTypes() throws Exception {
        assertEquals("text/css; charset=utf-8", send("GET", "/css/styles.css").headers().firstValue("Content-Type").orElse(""));
        assertEquals("text/javascript; charset=utf-8", send("GET", "/js/app.js").headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void headReturnsHeadersWithoutBody() throws Exception {
        HttpResponse<String> r = send("HEAD", "/");
        assertEquals(200, r.statusCode());
        assertEquals("", r.body());
    }

    @Test
    void missingFilesDirectoriesUnknownTypesAndHiddenFilesAre404() throws Exception {
        assertEquals(404, send("GET", "/nope.html").statusCode());
        assertEquals(404, send("GET", "/empty-dir/").statusCode(), "no directory listing");
        assertEquals(404, send("GET", "/empty-dir").statusCode());
        assertEquals(404, send("GET", "/notes.txt").statusCode(), "only known types are served");
        assertEquals(404, send("GET", "/.secret.html").statusCode(), "hidden files are never served");
    }

    @Test
    void pathTraversalAndSymlinkEscapesAre404() throws Exception {
        assertTrue(rawStatusLine("/../outside.html").contains("404"), rawStatusLine("/../outside.html"));
        assertTrue(rawStatusLine("/css/../../outside.html").contains("404"));
        assertTrue(rawStatusLine("/%2e%2e/outside.html").contains("404"));
        assertTrue(rawStatusLine("/css/%2e%2e/%2e%2e/outside.html").contains("404"));
        assertEquals(404, send("GET", "/escape.html").statusCode(), "symlink pointing outside the root");
        assertFalse(send("GET", "/escape.html").body().contains("outside the web root"));
    }

    @Test
    void onlyGetAndHeadAreAllowed() throws Exception {
        HttpResponse<String> r = send("POST", "/index.html");
        assertEquals(405, r.statusCode());
        assertEquals("GET, HEAD", r.headers().firstValue("Allow").orElse(""));
    }

    @Test
    void apiRoutesStillWinAndUnknownApiPathsKeepJsonErrors() throws Exception {
        HttpResponse<String> health = send("GET", "/api/health");
        assertEquals(200, health.statusCode());
        assertEquals("{\"status\":\"ok\"}", health.body());

        HttpResponse<String> unknown = send("GET", "/api/does-not-exist");
        assertEquals(404, unknown.statusCode());
        assertEquals("{\"error\":\"Not found\"}", unknown.body());
        assertTrue(unknown.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
    }

    @Test
    void missingFrontendDirectoryLeavesTheApiWorking() throws Exception {
        HttpServer other = App.createServer("127.0.0.1", 0);
        App.mountFrontend(other, temp.resolve("does-not-exist"));
        other.start();
        try {
            String url = "http://127.0.0.1:" + other.getAddress().getPort();
            HttpResponse<String> r = CLIENT.send(HttpRequest.newBuilder(URI.create(url + "/api/health")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
        } finally {
            other.stop(0);
        }
    }
}
