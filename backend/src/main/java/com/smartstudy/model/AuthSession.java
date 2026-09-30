package com.smartstudy.model;

import java.time.Instant;

/** A row of auth_sessions. The token hash is deliberately not carried around. */
public record AuthSession(long id, long studentId, Instant expiresAt) {
}
