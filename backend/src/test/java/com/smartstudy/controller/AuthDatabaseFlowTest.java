package com.smartstudy.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstudy.App;
import com.smartstudy.repository.Database;
import com.smartstudy.repository.JdbcSessionRepository;
import com.smartstudy.repository.JdbcStudentRepository;
import com.smartstudy.service.AuthService;
import com.smartstudy.support.DbTestSupport;
import com.smartstudy.support.SeedData;
import com.smartstudy.util.PasswordHasher;
import com.smartstudy.util.TokenGenerator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Full register -> login -> me -> logout flow against real MySQL (skipped unless DB_PASSWORD is set). */
class AuthDatabaseFlowTest {

    private static final String PASSWORD = "Sup3r-Secret-Pass";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private Database db;
    private HttpServer server;
    private String base;
    private String email;

    @BeforeEach
    void start() throws Exception {
        db = DbTestSupport.databaseOrSkip();
        AuthService service = new AuthService(new JdbcStudentRepository(db), new JdbcSessionRepository(db),
                new PasswordHasher(), new TokenGenerator(), Clock.systemUTC(), Duration.ofHours(1));
        server = App.createServer("127.0.0.1", 0, service);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        email = "flow-test-" + UUID.randomUUID() + "@example.test";
    }

    @AfterEach
    void stop() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        if (db != null && email != null) {
            DbTestSupport.deleteStudentByEmail(db, email);
        }
    }

    private HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String credentials(String email, String password) {
        return JSON.createObjectNode().put("email", email).put("password", password).toString();
    }

    @Test
    void fullFlowAgainstMySql() throws Exception {
        String registerBody = JSON.createObjectNode()
                .put("name", "Flow Tester").put("email", email).put("password", PASSWORD).toString();
        assertEquals(201, call("POST", "/api/auth/register", registerBody, null).statusCode());
        assertEquals(409, call("POST", "/api/auth/register", registerBody, null).statusCode());

        // The password is stored only as PBKDF2 output.
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT password_hash, password_salt FROM students WHERE email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertFalse(rs.getString(1).contains(PASSWORD));
                assertTrue(new PasswordHasher().verify(PASSWORD, rs.getString(1), rs.getString(2)));
            }
        }

        assertEquals(401, call("POST", "/api/auth/login", credentials(email, "Wrong-Pass-123"), null).statusCode());
        HttpResponse<String> login = call("POST", "/api/auth/login", credentials(email, PASSWORD), null);
        assertEquals(200, login.statusCode());
        String token = JSON.readTree(login.body()).get("token").asText();

        // The auth_sessions row holds the hash, never the token.
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT s.token_hash FROM auth_sessions s JOIN students st ON st.id = s.student_id "
                             + "WHERE st.email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(TokenGenerator.hash(token), rs.getString(1));
                assertFalse(rs.getString(1).equals(token));
                assertFalse(rs.next(), "exactly one session");
            }
        }

        assertEquals(401, call("GET", "/api/auth/me", null, null).statusCode());
        HttpResponse<String> me = call("GET", "/api/auth/me", null, token);
        assertEquals(200, me.statusCode());
        assertEquals(email, JSON.readTree(me.body()).get("email").asText());

        assertEquals(200, call("POST", "/api/auth/logout", null, token).statusCode());
        assertEquals(401, call("GET", "/api/auth/me", null, token).statusCode());

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM auth_sessions s JOIN students st ON st.id = s.student_id WHERE st.email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1), "revoked session row is gone");
            }
        }
    }

    @Test
    void expiredSessionInTheDatabaseIsRejectedAndRemoved() throws Exception {
        String registerBody = JSON.createObjectNode()
                .put("name", "Expiry Tester").put("email", email).put("password", PASSWORD).toString();
        call("POST", "/api/auth/register", registerBody, null);
        String token = JSON.readTree(call("POST", "/api/auth/login", credentials(email, PASSWORD), null).body())
                .get("token").asText();
        assertEquals(200, call("GET", "/api/auth/me", null, token).statusCode());

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE auth_sessions SET expires_at = ? WHERE token_hash = ?")) {
            ps.setTimestamp(1, java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)));
            ps.setString(2, TokenGenerator.hash(token));
            assertEquals(1, ps.executeUpdate());
        }
        assertEquals(401, call("GET", "/api/auth/me", null, token).statusCode());
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM auth_sessions WHERE token_hash = ?")) {
            ps.setString(1, TokenGenerator.hash(token));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void seededStudentCanLoginWithTheDevPassword() throws Exception {
        Assumptions.assumeTrue(SeedData.exists(), "seed.sql not available");
        String password = SeedData.devPassword();
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "SEED_TEST_PASSWORD not set - skipping seed password compatibility test");
        String seedEmail = SeedData.students().get(0).email();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM students WHERE email = ?")) {
            ps.setString(1, seedEmail);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Assumptions.assumeTrue(rs.getInt(1) == 1, "seed data not loaded in this database");
            }
        }
        HttpResponse<String> login = call("POST", "/api/auth/login", credentials(seedEmail, password), null);
        assertEquals(200, login.statusCode());
        JsonNode student = JSON.readTree(login.body()).get("student");
        assertEquals(seedEmail, student.get("email").asText());
        // The seed row is left in place; only the session this created is removed.
        String token = JSON.readTree(login.body()).get("token").asText();
        assertEquals(200, call("POST", "/api/auth/logout", null, token).statusCode());
    }
}
