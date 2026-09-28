package com.smartstudy.controller;

import com.smartstudy.App;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthControllerTest {

    private static HttpServer server;
    private static String base;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws IOException {
        server = App.createServer("127.0.0.1", 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void getHealthReturnsOk() throws Exception {
        HttpResponse<String> r = send("GET", "/api/health");
        assertEquals(200, r.statusCode());
        assertEquals("{\"status\":\"ok\"}", r.body());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
    }

    @Test
    void nonGetIsRejectedWith405() throws Exception {
        HttpResponse<String> r = send("POST", "/api/health");
        assertEquals(405, r.statusCode());
        assertEquals("GET", r.headers().firstValue("Allow").orElse(""));
    }

    @Test
    void subPathIsNotFound() throws Exception {
        assertEquals(404, send("GET", "/api/health/extra").statusCode());
    }

    @Test
    void unknownPathIsNotFound() throws Exception {
        assertEquals(404, send("GET", "/api/nothing").statusCode());
    }
}
