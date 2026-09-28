package com.smartstudy.repository;

import com.smartstudy.config.AppConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real MySQL connectivity check. Skipped (not passed) unless DB_PASSWORD is set,
 * so a plain `mvn test` never pretends the database was tested.
 */
class DatabaseConnectionTest {

    @Test
    void canConnectAndRunQuery() throws Exception {
        String pw = System.getenv("DB_PASSWORD");
        Assumptions.assumeTrue(pw != null && !pw.isBlank(), "DB_PASSWORD not set - skipping MySQL test");

        Database db = new Database(AppConfig.load());
        try (Connection c = db.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT 1")) {
            assertTrue(c.isValid(2));
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }
}
