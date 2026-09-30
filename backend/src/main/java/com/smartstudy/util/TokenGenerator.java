package com.smartstudy.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Opaque session tokens: 256 random bits (URL-safe Base64, 43 chars).
 * Only the SHA-256 hex digest is ever stored. A fast hash is appropriate here
 * because the token is high-entropy random data, not a human-chosen secret.
 */
public final class TokenGenerator {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random;

    public TokenGenerator() {
        this(new SecureRandom());
    }

    public TokenGenerator(SecureRandom random) {
        this.random = random;
    }

    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Lower-case hex SHA-256 (64 chars, fits auth_sessions.token_hash CHAR(64)). */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
