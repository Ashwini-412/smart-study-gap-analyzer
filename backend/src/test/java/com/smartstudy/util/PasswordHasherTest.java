package com.smartstudy.util;

import com.smartstudy.support.SeedData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashProducesBase64Of32ByteKeyAnd16ByteSalt() {
        PasswordHasher.Hashed h = hasher.hash("Correct-Horse-9");
        assertEquals(32, Base64.getDecoder().decode(h.hash()).length);
        assertEquals(16, Base64.getDecoder().decode(h.salt()).length);
        assertFalse(h.hash().contains("Correct-Horse-9"));
    }

    @Test
    void correctPasswordVerifies() {
        PasswordHasher.Hashed h = hasher.hash("Correct-Horse-9");
        assertTrue(hasher.verify("Correct-Horse-9", h.hash(), h.salt()));
    }

    @Test
    void incorrectPasswordFails() {
        PasswordHasher.Hashed h = hasher.hash("Correct-Horse-9");
        assertFalse(hasher.verify("Correct-Horse-8", h.hash(), h.salt()));
        assertFalse(hasher.verify("correct-horse-9", h.hash(), h.salt()));
        assertFalse(hasher.verify("", h.hash(), h.salt()));
    }

    @Test
    void differentPasswordsProduceDifferentHashes() {
        assertNotEquals(hasher.hash("Password-One1").hash(), hasher.hash("Password-Two2").hash());
    }

    @Test
    void samePasswordWithDifferentSaltsProducesDifferentStoredValues() {
        PasswordHasher.Hashed a = hasher.hash("Same-Password-1");
        PasswordHasher.Hashed b = hasher.hash("Same-Password-1");
        assertNotEquals(a.salt(), b.salt());
        assertNotEquals(a.hash(), b.hash());
        // ...and both still verify against their own salt, but not each other's.
        assertTrue(hasher.verify("Same-Password-1", a.hash(), a.salt()));
        assertTrue(hasher.verify("Same-Password-1", b.hash(), b.salt()));
        assertFalse(hasher.verify("Same-Password-1", a.hash(), b.salt()));
    }

    @Test
    void corruptStoredValuesNeverVerify() {
        PasswordHasher.Hashed h = hasher.hash("Correct-Horse-9");
        assertFalse(hasher.verify("Correct-Horse-9", "not base64!!", h.salt()));
        assertFalse(hasher.verify("Correct-Horse-9", h.hash(), "not base64!!"));
        assertFalse(hasher.verify("Correct-Horse-9", h.hash(), ""));
    }

    @Test
    void hashedToStringDoesNotLeakValues() {
        PasswordHasher.Hashed h = hasher.hash("Correct-Horse-9");
        assertFalse(h.toString().contains(h.hash()));
        assertFalse(h.toString().contains(h.salt()));
    }

    /** Proves PBKDF2WithHmacSHA256 / 210,000 iterations / 32-byte key / Base64 match what seed.sql holds. */
    @Test
    void existingSeedHashesVerifyWithTheDevPassword() throws IOException {
        String password = SeedData.devPassword();
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "SEED_TEST_PASSWORD not set - skipping seed password compatibility test");
        assertTrue(SeedData.exists(), "database/seed.sql not found relative to backend/");
        List<SeedData.SeedStudent> students = SeedData.students();
        assertEquals(4, students.size(), "expected the four seed students");
        for (SeedData.SeedStudent s : students) {
            assertTrue(hasher.verify(password, s.hash(), s.salt()), "seed hash for " + s.email());
            assertFalse(hasher.verify(password + "x", s.hash(), s.salt()), "wrong pw for " + s.email());
        }
    }
}
