package com.smartstudy.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigTest {

    private static AppConfig loadDefaults(Map<String, String> env) throws IOException {
        try (InputStream in = AppConfigTest.class.getClassLoader().getResourceAsStream("application.properties")) {
            return AppConfig.load(in, env::get);
        }
    }

    private static AppConfig loadText(String text, Map<String, String> env) throws IOException {
        return AppConfig.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), env::get);
    }

    private static final String BASE = """
            server.host=127.0.0.1
            server.port=8080
            db.host=localhost
            db.port=3306
            db.name=x
            db.username=u
            db.password=
            """;

    @Test
    void defaultsAreLoadedWhenEnvironmentIsEmpty() throws IOException {
        AppConfig c = loadDefaults(Map.of());
        assertEquals("127.0.0.1", c.serverHost());
        assertEquals(8080, c.serverPort());
        assertEquals("localhost", c.dbHost());
        assertEquals(3306, c.dbPort());
        assertEquals("smart_study_gap_analyzer", c.dbName());
        assertEquals("gap_app", c.dbUsername());
        assertEquals("", c.dbPassword());
        assertEquals(75.0, c.strongThreshold());
        assertEquals(50.0, c.moderateThreshold());
        assertEquals("jdbc:mysql://localhost:3306/smart_study_gap_analyzer", c.jdbcUrl());
    }

    @Test
    void environmentOverridesDefaults() throws IOException {
        AppConfig c = loadDefaults(Map.of("SERVER_PORT", "9090", "DB_PASSWORD", "s3cret", "DB_HOST", "db.internal"));
        assertEquals(9090, c.serverPort());
        assertEquals("s3cret", c.dbPassword());
        assertEquals("db.internal", c.dbHost());
    }

    @Test
    void toStringNeverContainsThePassword() throws IOException {
        AppConfig c = loadDefaults(Map.of("DB_PASSWORD", "s3cret"));
        assertFalse(c.toString().contains("s3cret"));
    }

    @Test
    void invalidPortIsRejected() {
        var ex = assertThrows(IllegalStateException.class, () -> loadDefaults(Map.of("SERVER_PORT", "99999")));
        assertTrue(ex.getMessage().contains("server.port"));
        assertThrows(IllegalStateException.class, () -> loadDefaults(Map.of("DB_PORT", "abc")));
    }

    @Test
    void moderateMustBeBelowStrong() {
        String text = BASE + "gap.threshold.strong=50\ngap.threshold.moderate=75\n";
        assertThrows(IllegalStateException.class, () -> loadText(text, Map.of()));
        String equal = BASE + "gap.threshold.strong=60\ngap.threshold.moderate=60\n";
        assertThrows(IllegalStateException.class, () -> loadText(equal, Map.of()));
    }

    @Test
    void thresholdsMustBePercentages() {
        String text = BASE + "gap.threshold.strong=120\ngap.threshold.moderate=50\n";
        assertThrows(IllegalStateException.class, () -> loadText(text, Map.of()));
    }

    @Test
    void customThresholdsAreAccepted() throws IOException {
        AppConfig c = loadText(BASE + "gap.threshold.strong=80\ngap.threshold.moderate=40\n", Map.of());
        assertEquals(80.0, c.strongThreshold());
        assertEquals(40.0, c.moderateThreshold());
    }

    @Test
    void sessionLifetimeDefaultsTo24HoursAndCanBeOverridden() throws IOException {
        assertEquals(24, loadDefaults(Map.of()).sessionHours());
        assertEquals(2, loadDefaults(Map.of("AUTH_SESSION_HOURS", "2")).sessionHours());
        // Property absent altogether (as in the inline configs above) also means 24.
        assertEquals(24, loadText(BASE + "gap.threshold.strong=75\ngap.threshold.moderate=50\n", Map.of()).sessionHours());
    }

    @Test
    void invalidSessionLifetimeIsRejected() {
        for (String bad : new String[]{"0", "-1", "721", "abc", ""}) {
            assertThrows(IllegalStateException.class,
                    () -> loadDefaults(Map.of("AUTH_SESSION_HOURS", bad)), "hours: '" + bad + "'");
        }
    }

    @Test
    void placeholderWithoutDefaultAndWithoutEnvFails() {
        String text = BASE.replace("db.name=x", "db.name=${MISSING_VAR}")
                + "gap.threshold.strong=75\ngap.threshold.moderate=50\n";
        var ex = assertThrows(IllegalStateException.class, () -> loadText(text, Map.of()));
        assertTrue(ex.getMessage().contains("MISSING_VAR"));
    }
}
