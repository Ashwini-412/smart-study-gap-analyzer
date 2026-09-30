package com.smartstudy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstudy.App;
import com.smartstudy.service.AuthService;
import com.smartstudy.service.QuestionService;
import com.smartstudy.service.QuizService;
import com.smartstudy.service.TopicService;
import com.smartstudy.support.InMemoryQuestionRepository;
import com.smartstudy.support.InMemoryQuizRepository;
import com.smartstudy.support.InMemorySessionRepository;
import com.smartstudy.support.InMemoryStudentRepository;
import com.smartstudy.support.InMemoryTopicRepository;
import com.smartstudy.support.MutableClock;
import com.smartstudy.util.PasswordHasher;
import com.smartstudy.util.TokenGenerator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Milestone 7: authentication INTEGRATION across every controller that mounts AuthFilter, not
 * just /api/auth/* (which AuthControllerTest and AuthFilterTest already cover thoroughly - those
 * are not duplicated here). This proves the same session mechanism that protects /api/auth/me
 * also correctly protects the quiz-management routes added in M4: an expired or revoked token
 * must not keep working there just because it was never explicitly tested against those paths
 * before. "Authenticated identity cannot be spoofed through JSON/body/query/path data" is already
 * covered by AuthControllerTest.meReflectsTheTokenOwnerNotAnyClientSuppliedIdentity and is not
 * duplicated here either - no current DTO accepts a studentId field at all (verified by
 * inspection), so there is nothing else to spoof yet.
 */
class AuthIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static HttpServer server;
    private static String base;
    private static MutableClock clock;

    @BeforeAll
    static void start() throws Exception {
        clock = new MutableClock(T0);
        AuthService authService = new AuthService(new InMemoryStudentRepository(), new InMemorySessionRepository(),
                new PasswordHasher(), new TokenGenerator(), clock, Duration.ofHours(24));
        TopicService topicService = new TopicService(new InMemoryTopicRepository());
        InMemoryQuizRepository quizRepo = new InMemoryQuizRepository();
        QuizService quizService = new QuizService(quizRepo);
        QuestionService questionService =
                new QuestionService(new InMemoryQuestionRepository(), quizRepo, new InMemoryTopicRepository());

        server = App.createServer("127.0.0.1", 0, authService, topicService, quizService, questionService);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @BeforeEach
    void resetClock() {
        clock.set(T0);
    }

    private record Res(int status, String body) {
    }

    private static Res call(String method, String path, String body, String bearerToken) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.method(method, HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json");
        }
        if (bearerToken != null) {
            b.header("Authorization", "Bearer " + bearerToken);
        }
        HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body());
    }

    private static String register(String email) throws Exception {
        String body = JSON.createObjectNode().put("name", "Auth Integration Tester").put("email", email)
                .put("password", "Sup3r-Secret-Pass").toString();
        assertEquals(201, call("POST", "/api/auth/register", body, null).status());
        String loginBody = JSON.createObjectNode().put("email", email).put("password", "Sup3r-Secret-Pass").toString();
        Res login = call("POST", "/api/auth/login", loginBody, null);
        assertEquals(200, login.status());
        return JSON.readTree(login.body()).get("token").asText();
    }

    private static String uniqueEmail() {
        return "auth-integration-" + UUID.randomUUID() + "@example.com";
    }

    // ---- public vs protected classification ----

    @Test
    void healthAndRegisterAndLoginArePublic() throws Exception {
        assertEquals(200, call("GET", "/api/health", null, null).status());
        String email = uniqueEmail();
        String registerBody = JSON.createObjectNode().put("name", "N").put("email", email)
                .put("password", "Sup3r-Secret-Pass").toString();
        assertEquals(201, call("POST", "/api/auth/register", registerBody, null).status());
        String loginBody = JSON.createObjectNode().put("email", email).put("password", "Sup3r-Secret-Pass").toString();
        assertEquals(200, call("POST", "/api/auth/login", loginBody, null).status());
    }

    @Test
    void meLogoutAndQuizManagementRoutesAllRequireAuthentication() throws Exception {
        for (String path : List.of("/api/auth/me", "/api/auth/logout", "/api/topics", "/api/quizzes",
                "/api/quizzes/1", "/api/quizzes/1/questions")) {
            assertEquals(401, call("GET", path, null, null).status(), path);
        }
    }

    // ---- a valid token works everywhere it's supposed to ----

    @Test
    void aValidTokenGrantsAccessToEveryProtectedRoute() throws Exception {
        String token = register(uniqueEmail());
        assertEquals(200, call("GET", "/api/auth/me", null, token).status());
        assertEquals(200, call("GET", "/api/topics", null, token).status());
        assertEquals(200, call("GET", "/api/quizzes", null, token).status());
    }

    // ---- invalid / malformed tokens are rejected everywhere, not just on /api/auth/me ----

    @Test
    void unknownTokenIsRejectedOnQuizManagementRoutesToo() throws Exception {
        String garbage = "A".repeat(43);
        assertEquals(401, call("GET", "/api/topics", null, garbage).status());
        assertEquals(401, call("GET", "/api/quizzes", null, garbage).status());
    }

    @Test
    void malformedAuthorizationHeaderIsRejectedOnQuizManagementRoutesToo() throws Exception {
        String token = register(uniqueEmail());
        for (String header : new String[]{token, "Bearer", "Basic " + token, "Bearer " + token + " extra"}) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/topics"))
                    .header("Authorization", header).GET().build();
            assertEquals(401, CLIENT.send(req, HttpResponse.BodyHandlers.ofString()).statusCode(),
                    "should reject: " + header.replace(token, "<token>"));
        }
    }

    // ---- expiry and revocation propagate to every protected route, not just /api/auth/me ----

    @Test
    void expiredTokenIsRejectedOnQuizManagementRoutesToo() throws Exception {
        String token = register(uniqueEmail());
        assertEquals(200, call("GET", "/api/topics", null, token).status(), "valid just before expiry");

        clock.advance(Duration.ofHours(24));

        assertEquals(401, call("GET", "/api/auth/me", null, token).status());
        assertEquals(401, call("GET", "/api/topics", null, token).status());
        assertEquals(401, call("GET", "/api/quizzes", null, token).status());
    }

    @Test
    void logoutRevokesAccessToQuizManagementRoutesToo() throws Exception {
        String token = register(uniqueEmail());
        assertEquals(200, call("GET", "/api/topics", null, token).status());

        assertEquals(200, call("POST", "/api/auth/logout", null, token).status());

        assertEquals(401, call("GET", "/api/auth/me", null, token).status());
        assertEquals(401, call("GET", "/api/topics", null, token).status());
        assertEquals(401, call("GET", "/api/quizzes", null, token).status());
    }

    @Test
    void loggingOutOneStudentDoesNotAffectAnothersAccessToQuizManagementRoutes() throws Exception {
        String tokenA = register(uniqueEmail());
        String tokenB = register(uniqueEmail());

        assertEquals(200, call("POST", "/api/auth/logout", null, tokenA).status());

        assertEquals(401, call("GET", "/api/topics", null, tokenA).status());
        assertEquals(200, call("GET", "/api/topics", null, tokenB).status(), "other student's session is untouched");
    }

    // ---- authenticated identity reaches the correct downstream controller/service ----

    @Test
    void eachStudentsTokenResolvesToTheirOwnIdentityDownstream() throws Exception {
        String emailA = uniqueEmail();
        String emailB = uniqueEmail();
        String tokenA = register(emailA);
        String tokenB = register(emailB);

        String meA = call("GET", "/api/auth/me", null, tokenA).body();
        String meB = call("GET", "/api/auth/me", null, tokenB).body();
        assertEquals(emailA, JSON.readTree(meA).get("email").asText());
        assertEquals(emailB, JSON.readTree(meB).get("email").asText());
    }
}
