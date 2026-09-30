package com.smartstudy.util;

/**
 * One-line description of an exception and its causes for the server log. Only exception
 * types and (truncated) messages are included: never request bodies, headers or tokens.
 */
public final class ErrorLog {

    private static final int MAX_MESSAGE = 200;

    private ErrorLog() {
    }

    public static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (Throwable c = t; c != null && depth < 5; c = c.getCause(), depth++) {
            if (depth > 0) {
                sb.append(" <- caused by ");
            }
            sb.append(c.getClass().getName());
            String m = c.getMessage();
            if (m != null && !m.isBlank()) {
                String oneLine = m.replaceAll("\\s+", " ");
                sb.append(": ").append(oneLine.length() > MAX_MESSAGE ? oneLine.substring(0, MAX_MESSAGE) + "..." : oneLine);
            }
        }
        return sb.toString();
    }
}
