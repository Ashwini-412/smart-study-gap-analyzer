package com.smartstudy.service;

import com.smartstudy.dto.AuthenticatedUser;
import com.smartstudy.dto.LoginRequest;
import com.smartstudy.dto.LoginResponse;
import com.smartstudy.dto.RegisterRequest;
import com.smartstudy.dto.StudentResponse;
import com.smartstudy.support.InMemorySessionRepository;
import com.smartstudy.support.InMemoryStudentRepository;
import com.smartstudy.support.MutableClock;
import com.smartstudy.util.AuthenticationException;
import com.smartstudy.util.DuplicateEmailException;
import com.smartstudy.util.PasswordHasher;
import com.smartstudy.util.TokenGenerator;
import com.smartstudy.util.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final String PASSWORD = "Sup3r-Secret-Pass";

    private InMemoryStudentRepository students;
    private InMemorySessionRepository sessions;
    private MutableClock clock;
    private AuthService service;

    @BeforeEach
    void setUp() {
        students = new InMemoryStudentRepository();
        sessions = new InMemorySessionRepository();
        clock = new MutableClock(T0);
        service = new AuthService(students, sessions, new PasswordHasher(), new TokenGenerator(), clock,
                Duration.ofHours(24));
    }

    private StudentResponse register(String email) {
        return service.register(new RegisterRequest("Test Student", email, PASSWORD));
    }

    private ValidationException registerInvalid(String name, String email, String password) {
        return assertThrows(ValidationException.class,
                () -> service.register(new RegisterRequest(name, email, password)));
    }

    // ---- registration ----

    @Test
    void registrationSucceedsAndStoresOnlyAHash() {
        StudentResponse r = service.register(new RegisterRequest("  Asha Rao ", "  Asha@Example.com ", PASSWORD));
        assertEquals("Asha Rao", r.name());
        assertEquals("asha@example.com", r.email()); // trimmed + lower-cased
        var stored = students.findById(r.id()).orElseThrow();
        assertNotEquals(PASSWORD, stored.passwordHash());
        assertFalse(stored.passwordHash().contains(PASSWORD));
        assertTrue(new PasswordHasher().verify(PASSWORD, stored.passwordHash(), stored.passwordSalt()));
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() {
        register("dup@example.com");
        assertThrows(DuplicateEmailException.class, () -> register("dup@example.com"));
        assertThrows(DuplicateEmailException.class, () -> register("DUP@Example.com"));
        assertEquals(1, students.count());
    }

    @Test
    void missingNameIsRejected() {
        assertTrue(registerInvalid(null, "a@example.com", PASSWORD).fieldErrors().containsKey("name"));
        assertTrue(registerInvalid("   ", "a@example.com", PASSWORD).fieldErrors().containsKey("name"));
        assertEquals(0, students.count());
    }

    @Test
    void invalidNamesAreRejected() {
        assertTrue(registerInvalid("x".repeat(101), "a@example.com", PASSWORD).fieldErrors().containsKey("name"));
        assertTrue(registerInvalid("Bad\u0000Name", "a@example.com", PASSWORD).fieldErrors().containsKey("name"));
    }

    @Test
    void invalidEmailsAreRejected() {
        for (String bad : new String[]{null, "", "   ", "plainaddress", "@example.com", "a@", "a@b", "a b@example.com",
                "a@@example.com", "a@example.c", "a@exam ple.com"}) {
            assertTrue(registerInvalid("Name", bad, PASSWORD).fieldErrors().containsKey("email"), "email: " + bad);
        }
        String tooLong = "a".repeat(250) + "@example.com";
        assertTrue(registerInvalid("Name", tooLong, PASSWORD).fieldErrors().containsKey("email"));
    }

    @Test
    void invalidPasswordsAreRejected() {
        for (String bad : new String[]{null, "", "short1", "allletters", "12345678", "a".repeat(129) + "1"}) {
            assertTrue(registerInvalid("Name", "a@example.com", bad).fieldErrors().containsKey("password"),
                    "password: " + bad);
        }
    }

    @Test
    void validationReportsEveryBadFieldAndNeverEchoesInput() {
        ValidationException e = registerInvalid("", "nope", "short");
        assertEquals(3, e.fieldErrors().size());
        assertFalse(e.fieldErrors().values().stream().anyMatch(m -> m.contains("nope") || m.contains("short")));
    }

    // ---- login ----

    @Test
    void loginSucceedsAndStoresOnlyTheTokenHash() {
        StudentResponse s = register("login@example.com");
        LoginResponse r = service.login(new LoginRequest("login@example.com", PASSWORD));

        assertEquals("Bearer", r.tokenType());
        assertEquals(s.id(), r.student().id());
        assertEquals(43, r.token().length());
        assertEquals(T0.plus(Duration.ofHours(24)).toString(), r.expiresAt());

        assertEquals(1, sessions.count());
        String stored = sessions.storedTokenHashes().get(0);
        assertNotEquals(r.token(), stored);
        assertEquals(TokenGenerator.hash(r.token()), stored);
        assertEquals(64, stored.length());
    }

    @Test
    void loginEmailIsCaseInsensitive() {
        register("case@example.com");
        assertEquals("case@example.com",
                service.login(new LoginRequest("  CASE@example.com ", PASSWORD)).student().email());
    }

    @Test
    void eachLoginGetsADistinctToken() {
        register("multi@example.com");
        String a = service.login(new LoginRequest("multi@example.com", PASSWORD)).token();
        String b = service.login(new LoginRequest("multi@example.com", PASSWORD)).token();
        assertNotEquals(a, b);
        assertEquals(2, sessions.count());
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameError() {
        register("real@example.com");
        var wrongPw = assertThrows(AuthenticationException.class,
                () -> service.login(new LoginRequest("real@example.com", "Wrong-Pass-123")));
        var noUser = assertThrows(AuthenticationException.class,
                () -> service.login(new LoginRequest("ghost@example.com", PASSWORD)));
        assertEquals(wrongPw.getMessage(), noUser.getMessage());
        assertEquals(0, sessions.count()); // failed logins create no session
    }

    @Test
    void loginRequiresEmailAndPassword() {
        assertThrows(ValidationException.class, () -> service.login(new LoginRequest(null, PASSWORD)));
        assertThrows(ValidationException.class, () -> service.login(new LoginRequest("a@example.com", "")));
    }

    // ---- session validation ----

    private String loginToken(String email) {
        register(email);
        return service.login(new LoginRequest(email, PASSWORD)).token();
    }

    @Test
    void validTokenAuthenticates() {
        String token = loginToken("valid@example.com");
        AuthenticatedUser u = service.authenticate(token).orElseThrow();
        assertEquals("valid@example.com", u.email());
        assertEquals("Test Student", u.name());
    }

    @Test
    void unknownTokenIsRejected() {
        loginToken("x@example.com");
        assertTrue(service.authenticate("A".repeat(43)).isEmpty());
    }

    @Test
    void tokenIsRejectedOnceExpired() {
        String token = loginToken("exp@example.com");
        clock.set(T0.plus(Duration.ofHours(24)).minusSeconds(1));
        assertTrue(service.authenticate(token).isPresent(), "still valid just before expiry");
        clock.set(T0.plus(Duration.ofHours(24)));
        assertTrue(service.authenticate(token).isEmpty(), "expired at the expiry instant");
        assertEquals(0, sessions.count(), "expired session is cleaned up");
    }

    @Test
    void revokedTokenIsRejected() {
        String token = loginToken("rev@example.com");
        AuthenticatedUser u = service.authenticate(token).orElseThrow();
        service.logout(u.sessionId());
        assertTrue(service.authenticate(token).isEmpty());
    }

    @Test
    void logoutRevokesOnlyThatSession() {
        register("two@example.com");
        String first = service.login(new LoginRequest("two@example.com", PASSWORD)).token();
        String second = service.login(new LoginRequest("two@example.com", PASSWORD)).token();
        service.logout(service.authenticate(first).orElseThrow().sessionId());
        assertTrue(service.authenticate(first).isEmpty());
        assertTrue(service.authenticate(second).isPresent());
    }

    @Test
    void identityComesFromTheSessionNotFromAnythingElse() {
        String tokenA = loginToken("a@example.com");
        String tokenB = loginToken("b@example.com");
        assertEquals("a@example.com", service.authenticate(tokenA).map(AuthenticatedUser::email).orElseThrow());
        assertEquals("b@example.com", service.authenticate(tokenB).map(AuthenticatedUser::email).orElseThrow());
        Optional<AuthenticatedUser> none = service.authenticate("B".repeat(43));
        assertTrue(none.isEmpty());
    }
}
