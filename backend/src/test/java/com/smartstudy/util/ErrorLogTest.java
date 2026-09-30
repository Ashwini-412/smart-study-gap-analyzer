package com.smartstudy.util;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorLogTest {

    @Test
    void includesTheCauseChain() {
        Exception e = new IllegalStateException("outer", new SQLException("No suitable driver"));
        String s = ErrorLog.describe(e);
        assertTrue(s.contains("java.lang.IllegalStateException: outer"));
        assertTrue(s.contains("caused by java.sql.SQLException: No suitable driver"));
    }

    @Test
    void truncatesLongMessagesAndCollapsesNewlines() {
        String s = ErrorLog.describe(new RuntimeException("a\nb " + "x".repeat(1000)));
        assertFalse(s.contains("\n"));
        assertTrue(s.length() < 300);
        assertTrue(s.endsWith("..."));
    }

    @Test
    void handlesMissingMessageAndSelfReferencingCauses() {
        assertEquals("java.lang.RuntimeException", ErrorLog.describe(new RuntimeException()));
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b); // cycle: must terminate
        assertTrue(ErrorLog.describe(a).length() < 500);
    }
}
