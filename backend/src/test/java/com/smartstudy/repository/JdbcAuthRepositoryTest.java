package com.smartstudy.repository;

import com.smartstudy.model.AuthSession;
import com.smartstudy.model.Student;
import com.smartstudy.support.DbTestSupport;
import com.smartstudy.util.DuplicateEmailException;
import com.smartstudy.util.TokenGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-MySQL repository tests (skipped unless DB_PASSWORD is set). */
class JdbcAuthRepositoryTest {

    private Database db;
    private JdbcStudentRepository students;
    private JdbcSessionRepository sessions;
    private String email;

    @BeforeEach
    void setUp() {
        db = DbTestSupport.databaseOrSkip();
        students = new JdbcStudentRepository(db);
        sessions = new JdbcSessionRepository(db);
        email = "repo-test-" + UUID.randomUUID() + "@example.test";
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (db != null && email != null) {
            DbTestSupport.deleteStudentByEmail(db, email);
        }
    }

    @Test
    void createAndFindStudent() {
        Student created = students.create("Repo Tester", email, "hash-value", "salt-value");
        assertTrue(created.id() > 0);

        Student byEmail = students.findByEmail(email).orElseThrow();
        assertEquals(created.id(), byEmail.id());
        assertEquals("Repo Tester", byEmail.name());
        assertEquals("hash-value", byEmail.passwordHash());
        assertEquals("salt-value", byEmail.passwordSalt());
        assertEquals(email, students.findById(created.id()).orElseThrow().email());
    }

    @Test
    void unknownStudentIsEmpty() {
        assertTrue(students.findByEmail("nobody-" + UUID.randomUUID() + "@example.test").isEmpty());
        assertTrue(students.findById(Long.MAX_VALUE).isEmpty());
    }

    @Test
    void duplicateEmailIsReportedAsDuplicateEmailException() {
        students.create("First", email, "h", "s");
        assertThrows(DuplicateEmailException.class, () -> students.create("Second", email, "h", "s"));
        // MySQL's default collation is case-insensitive, so this is a duplicate too.
        assertThrows(DuplicateEmailException.class, () -> students.create("Third", email.toUpperCase(), "h", "s"));
    }

    @Test
    void sqlInjectionAttemptsAreTreatedAsPlainData() {
        String evil = "x'; DROP TABLE students; --@example.test";
        assertTrue(students.findByEmail(evil).isEmpty());
        Student created = students.create("Robert'); DROP TABLE students;--", email, "h", "s");
        assertEquals("Robert'); DROP TABLE students;--", students.findById(created.id()).orElseThrow().name());
        assertTrue(students.findByEmail(email).isPresent(), "table still exists");
    }

    @Test
    void sessionLifecycleStoresOnlyTheHash() throws Exception {
        Student s = students.create("Session Tester", email, "h", "s");
        String rawToken = new TokenGenerator().generate();
        String tokenHash = TokenGenerator.hash(rawToken);
        Instant expires = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        sessions.create(s.id(), tokenHash, expires);

        // What the database actually holds:
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT token_hash FROM auth_sessions WHERE student_id = ?")) {
            ps.setLong(1, s.id());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(tokenHash, rs.getString(1));
                assertFalse(rs.getString(1).contains(rawToken));
                assertEquals(64, rs.getString(1).length());
                assertFalse(rs.next());
            }
        }

        AuthSession found = sessions.findByTokenHash(tokenHash).orElseThrow();
        assertEquals(s.id(), found.studentId());
        assertTrue(sessions.findByTokenHash(rawToken).isEmpty(), "raw token must not match anything");

        sessions.deleteById(found.id());
        assertTrue(sessions.findByTokenHash(tokenHash).isEmpty());
    }

    @Test
    void expiryRoundTripsAsTheSameInstantInAnyJvmTimeZone() {
        Student s = students.create("TZ Tester", email, "h", "s");
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : new String[]{"UTC", "America/New_York", "Asia/Kolkata", "Pacific/Auckland"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                Instant expires = Instant.parse("2031-03-15T12:34:56Z");
                String hash = TokenGenerator.hash("tz-" + zone + "-" + UUID.randomUUID());
                sessions.create(s.id(), hash, expires);
                assertEquals(expires, sessions.findByTokenHash(hash).orElseThrow().expiresAt(), "zone " + zone);
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void deletingAStudentRemovesTheirSessions() {
        Student s = students.create("Cascade Tester", email, "h", "s");
        String hash = TokenGenerator.hash("cascade-" + UUID.randomUUID());
        sessions.create(s.id(), hash, Instant.now().plusSeconds(3600));
        assertTrue(sessions.findByTokenHash(hash).isPresent());
        try {
            DbTestSupport.deleteStudentByEmail(db, email);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        Optional<AuthSession> gone = sessions.findByTokenHash(hash);
        assertTrue(gone.isEmpty());
    }
}
