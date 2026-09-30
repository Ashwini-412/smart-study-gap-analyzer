package com.smartstudy.dto;

/**
 * Identity resolved from a validated server-side session. This is the only trusted
 * source of "who is calling"; nothing about identity is read from request bodies.
 */
public record AuthenticatedUser(long studentId, String name, String email, long sessionId) {

    /** Exchange attribute under which the auth filter publishes the caller. */
    public static final String ATTRIBUTE = "authenticatedUser";
}
