package com.smartstudy.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstudy.App;
import com.smartstudy.service.AuthService;
import com.smartstudy.support.InMemorySessionRepository;
import com.smartstudy.support.InMemoryStudentRepository;
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
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end over real HTTP: server + filter + controller + service, with in-memory repositories. */
class AuthControllerTest {

    private static final String PASSWORD = "Sup3r-Secret-Pass";
    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static HttpServer server;
    private static String base;
    private static InMemorySessionRepository sessions;
    private static MutableClock clock;

    @BeforeAll
    static void start() throws Exception {
        sessions = new InMemorySessionRepository();
        clock = new MutableClock(T0);
        AuthService service = new AuthService(new InMemoryStudentRepository(), sessions, new PasswordHasher(),
                new TokenGenerator(), clock, Duration.ofHours(24));
        server = App.createServer("127.0.0.1", 0, service);
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

    // ---- helpers ----

    private record Res(int status, String body, HttpResponse<String> raw) {
        JsonNode json() throws Exception {
            return JSON.readTree(body);
        }
    }

    private static Res call(String method, String path, String jsonBody, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (jsonBody == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.method(method, HttpRequest.BodyPublishers.ofString(jsonBody)).header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body(), r);
    }

    private static String registerBody(String name, String email, String password) {
        return JSON.createObjectNode().put("name", name).put("email", email).put("password", password).toString();
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private static Res register(String email) throws Exception {
        return call("POST", "/api/auth/register", registerBody("Test Student", email, PASSWORD));
    }

    private static Res login(String email, String password) throws Exception {
        return call("POST", "/api/auth/login", JSON.createObjectNode().put("email", email).put("password", password).toString());
    }

    /** Registers a fresh student and logs in; returns the raw token. */
    private static String freshToken() throws Exception {
        String email = uniqueEmail();
        assertEquals(201, register(email).status());
        return login(email, PASSWORD).json().get("token").asText();
    }

    private static Res me(String token) throws Exception {
        return call("GET", "/api/auth/me", null, "Authorization", "Bearer " + token);
    }

    private static void assertNoSecrets(String body, String... secretValues) {
        String lower = body.toLowerCase();
        for (String forbidden : List.of("password", "hash", "salt")) {
            assertFalse(lower.contains(forbidden), "response must not mention '" + forbidden + "': " + body);
        }
        for (String v : secretValues) {
            assertFalse(body.contains(v), "response must not contain a secret value");
        }
    }

    // ---- registration ----

    @Test
    void registerSucceeds() throws Exception {
        String email = uniqueEmail();
        Res r = register(email);
        assertEquals(201, r.status());
        JsonNode j = r.json();
        assertEquals(email, j.get("email").asText());
        assertEquals("Test Student", j.get("name").asText());
        assertTrue(j.get("id").isNumber());
        assertEquals(Set.of("id", "name", "email"), fieldNames(j));
        assertNoSecrets(r.body(), PASSWORD);
        assertEquals("no-store", r.raw().headers().firstValue("Cache-Control").orElse(""));
    }

    @Test
    void registerDuplicateEmailIsRejectedWith409() throws Exception {
        String email = uniqueEmail();
        assertEquals(201, register(email).status());
        Res dup = register(email);
        assertEquals(409, dup.status());
        assertNoSecrets(dup.body(), PASSWORD);
    }

    @Test
    void registerMissingNameIsRejected() throws Exception {
        Res r = call("POST", "/api/auth/register",
                JSON.createObjectNode().put("email", uniqueEmail()).put("password", PASSWORD).toString());
        assertEquals(400, r.status());
        assertTrue(r.json().get("fields").has("name"));
    }

    @Test
    void registerInvalidEmailIsRejected() throws Exception {
        Res r = call("POST", "/api/auth/register", registerBody("Name", "not-an-email", PASSWORD));
        assertEquals(400, r.status());
        assertTrue(r.json().get("fields").has("email"));
        assertFalse(r.body().contains("not-an-email"), "input must not be echoed");
    }

    @Test
    void registerInvalidPasswordIsRejectedWithoutEchoingIt() throws Exception {
        Res r = call("POST", "/api/auth/register", registerBody("Name", uniqueEmail(), "weakpass"));
        assertEquals(400, r.status());
        assertTrue(r.json().get("fields").has("password"));
        assertFalse(r.body().contains("weakpass"));
    }

    @Test
    void registerRejectsMalformedAndNonStringBodies() throws Exception {
        assertEquals(400, call("POST", "/api/auth/register", "{not json").status());
        assertEquals(400, call("POST", "/api/auth/register", "").status());
        assertEquals(400, call("POST", "/api/auth/register", "null").status());
        assertEquals(400, call("POST", "/api/auth/register", "[]").status());
        // A number where a string is expected must not be quietly accepted as a password.
        Res num = call("POST", "/api/auth/register",
                "{\"name\":\"N\",\"email\":\"" + uniqueEmail() + "\",\"password\":12345678}");
        assertEquals(400, num.status());
        // Oversized body
        assertEquals(400, call("POST", "/api/auth/register", "{\"name\":\"" + "x".repeat(20_000) + "\"}").status());
    }

    @Test
    void parserErrorsNeverEchoThePasswordBack() throws Exception {
        Res r = call("POST", "/api/auth/register", "{\"password\": \"" + PASSWORD + "\" oops");
        assertEquals(400, r.status());
        assertFalse(r.body().contains(PASSWORD));
    }

    // ---- login ----

    @Test
    void loginSucceedsAndCreatesASessionStoringOnlyTheHash() throws Exception {
        String email = uniqueEmail();
        register(email);
        int before = sessions.count();

        Res r = login(email, PASSWORD);
        assertEquals(200, r.status());
        JsonNode j = r.json();
        String token = j.get("token").asText();
        assertEquals("Bearer", j.get("tokenType").asText());
        assertEquals(T0.plus(Duration.ofHours(24)).toString(), j.get("expiresAt").asText());
        assertEquals(email, j.get("student").get("email").asText());
        assertEquals(Set.of("token", "tokenType", "expiresAt", "student"), fieldNames(j));
        assertEquals(Set.of("id", "name", "email"), fieldNames(j.get("student")));
        assertNoSecrets(r.body().replace(token, "<token>"), PASSWORD);

        assertEquals(before + 1, sessions.count());
        assertFalse(sessions.storedTokenHashes().contains(token), "raw token must not be stored");
        assertTrue(sessions.storedTokenHashes().contains(TokenGenerator.hash(token)));
    }

    @Test
    void loginWithWrongPasswordOrUnknownEmailIsRejectedIdentically() throws Exception {
        String email = uniqueEmail();
        register(email);
        Res wrong = login(email, "Wrong-Pass-123");
        Res unknown = login(uniqueEmail(), PASSWORD);
        assertEquals(401, wrong.status());
        assertEquals(401, unknown.status());
        assertEquals(wrong.body(), unknown.body());
        // The message says "Invalid email or password" (a label, not a value): check fields and values instead.
        assertEquals(Set.of("error"), fieldNames(wrong.json()));
        assertFalse(wrong.body().contains(PASSWORD) || wrong.body().contains("Wrong-Pass-123"));
        assertEquals("Bearer", wrong.raw().headers().firstValue("WWW-Authenticate").orElse(""));
    }

    @Test
    void loginWithMissingFieldsIsABadRequest() throws Exception {
        assertEquals(400, call("POST", "/api/auth/login", "{}").status());
        assertEquals(400, call("POST", "/api/auth/login", "{\"email\":\"a@example.com\"}").status());
    }

    // ---- authentication of protected endpoints ----

    @Test
    void meWithoutAuthorizationHeaderIs401() throws Exception {
        Res r = call("GET", "/api/auth/me", null);
        assertEquals(401, r.status());
        assertEquals("Bearer", r.raw().headers().firstValue("WWW-Authenticate").orElse(""));
    }

    @Test
    void meWithMalformedAuthorizationHeadersIs401() throws Exception {
        String token = freshToken();
        for (String header : new String[]{
                token,                       // no scheme
                "Bearer",                    // no token
                "Bearer ",                   // empty token
                "Basic " + token,            // wrong scheme
                "Bearer " + token + " x",    // trailing junk
                "Bearer  " + token,          // extra space
                "Token " + token,
                "Bearer a b",
                "Bearer short",
                "Bearer " + "A".repeat(500),
        }) {
            assertEquals(401, call("GET", "/api/auth/me", null, "Authorization", header).status(),
                    "should reject header: " + header.replaceAll("[A-Za-z0-9_-]{30,}", "<token>"));
        }
    }

    @Test
    void meWithUnknownTokenIs401() throws Exception {
        assertEquals(401, me("A".repeat(43)).status());
    }

    @Test
    void meWithExpiredTokenIs401() throws Exception {
        String token = freshToken();
        assertEquals(200, me(token).status());
        clock.advance(Duration.ofHours(24));
        assertEquals(401, me(token).status());
    }

    @Test
    void schemeNameIsCaseInsensitive() throws Exception {
        String token = freshToken();
        assertEquals(200, call("GET", "/api/auth/me", null, "Authorization", "bearer " + token).status());
    }

    // ---- /me ----

    @Test
    void meReturnsTheAuthenticatedStudentAndNoSecrets() throws Exception {
        String email = uniqueEmail();
        register(email);
        String token = login(email, PASSWORD).json().get("token").asText();

        Res r = me(token);
        assertEquals(200, r.status());
        JsonNode j = r.json();
        assertEquals(email, j.get("email").asText());
        assertEquals("Test Student", j.get("name").asText());
        assertEquals(Set.of("id", "name", "email"), fieldNames(j));
        assertNoSecrets(r.body().replace(token, "<token>"), PASSWORD, token, TokenGenerator.hash(token));
    }

    @Test
    void meReflectsTheTokenOwnerNotAnyClientSuppliedIdentity() throws Exception {
        String emailA = uniqueEmail();
        String emailB = uniqueEmail();
        register(emailA);
        register(emailB);
        String tokenA = login(emailA, PASSWORD).json().get("token").asText();
        long idB = login(emailB, PASSWORD).json().get("student").get("id").asLong();

        Res r = call("GET", "/api/auth/me?studentId=" + idB + "&user_id=" + idB, null,
                "Authorization", "Bearer " + tokenA, "X-Student-Id", String.valueOf(idB));
        assertEquals(200, r.status());
        assertEquals(emailA, r.json().get("email").asText());
        assertNotEquals(idB, r.json().get("id").asLong());
    }

    // ---- logout ----

    @Test
    void logoutWithoutAuthenticationIs401() throws Exception {
        assertEquals(401, call("POST", "/api/auth/logout", null).status());
        assertEquals(401, call("POST", "/api/auth/logout", null, "Authorization", "Bearer " + "A".repeat(43)).status());
    }

    @Test
    void logoutRevokesTheTokenImmediately() throws Exception {
        String token = freshToken();
        assertEquals(200, me(token).status());

        Res out = call("POST", "/api/auth/logout", null, "Authorization", "Bearer " + token);
        assertEquals(200, out.status());
        assertFalse(out.body().contains(token));

        assertEquals(401, me(token).status(), "token must be dead after logout");
        assertEquals(401, call("POST", "/api/auth/logout", null, "Authorization", "Bearer " + token).status(),
                "and cannot be used to log out again");
    }

    @Test
    void logoutOnlyRevokesTheCurrentSession() throws Exception {
        String email = uniqueEmail();
        register(email);
        String first = login(email, PASSWORD).json().get("token").asText();
        String second = login(email, PASSWORD).json().get("token").asText();
        call("POST", "/api/auth/logout", null, "Authorization", "Bearer " + first);
        assertEquals(401, me(first).status());
        assertEquals(200, me(second).status());
    }

    // ---- routing ----

    @Test
    void wrongMethodsAndPathsAreRejected() throws Exception {
        assertEquals(405, call("GET", "/api/auth/register", null).status());
        assertEquals(405, call("GET", "/api/auth/login", null).status());
        assertEquals(404, call("POST", "/api/auth/register/extra", "{}").status());
        String token = freshToken();
        assertEquals(405, call("POST", "/api/auth/me", null, "Authorization", "Bearer " + token).status());
        assertEquals(405, call("GET", "/api/auth/logout", null, "Authorization", "Bearer " + token).status());
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new java.util.HashSet<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }
}
