package com.smartstudy.repository;

import com.smartstudy.model.AuthSession;

import java.time.Instant;
import java.util.Optional;

/**
 * Login sessions, keyed by the SHA-256 hash of the token; raw tokens never reach this layer.
 * A session is revoked by deleting its row, so a revoked session simply no longer exists.
 */
public interface SessionRepository {

    void create(long studentId, String tokenHash, Instant expiresAt);

    Optional<AuthSession> findByTokenHash(String tokenHash);

    void deleteById(long sessionId);
}
