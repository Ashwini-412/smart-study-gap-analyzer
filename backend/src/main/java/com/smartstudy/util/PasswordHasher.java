package com.smartstudy.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PBKDF2WithHmacSHA256, 210,000 iterations, 32-byte key, 16-byte random salt,
 * both stored as Base64. This format matches the hashes in database/seed.sql.
 */
public final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    static final int ITERATIONS = 210_000;
    static final int KEY_BYTES = 32;
    static final int SALT_BYTES = 16;

    /** Base64 hash and salt, exactly as stored in students.password_hash / password_salt. */
    public record Hashed(String hash, String salt) {
        @Override
        public String toString() {
            return "Hashed[redacted]";
        }
    }

    private final SecureRandom random;

    public PasswordHasher() {
        this(new SecureRandom());
    }

    public PasswordHasher(SecureRandom random) {
        this.random = random;
    }

    /** Hashes with a fresh random salt. */
    public Hashed hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] key = derive(password, salt);
        return new Hashed(Base64.getEncoder().encodeToString(key), Base64.getEncoder().encodeToString(salt));
    }

    /** Constant-time check of a password against a stored Base64 hash/salt. */
    public boolean verify(String password, String storedHash, String storedSalt) {
        byte[] salt;
        byte[] expected;
        try {
            salt = Base64.getDecoder().decode(storedSalt);
            expected = Base64.getDecoder().decode(storedHash);
        } catch (IllegalArgumentException e) {
            return false; // corrupt stored value: never authenticates
        }
        if (salt.length == 0) {
            return false;
        }
        return MessageDigest.isEqual(derive(password, salt), expected);
    }

    private static byte[] derive(String password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " is unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }
}
