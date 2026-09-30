package com.smartstudy.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the students out of the real database/seed.sql so tests verify the committed file,
 * not a copy of it. The development-only password shared by all seed students is never
 * hardcoded here; it comes from the SEED_TEST_PASSWORD environment variable so it isn't
 * committed to source (the database only ever holds its PBKDF2 hashes).
 */
public final class SeedData {

    public record SeedStudent(String email, String hash, String salt) {
    }

    /** The seed students' shared password, or null if SEED_TEST_PASSWORD isn't configured. */
    public static String devPassword() {
        return System.getenv("SEED_TEST_PASSWORD");
    }

    private static final Path SEED = Path.of("..", "database", "seed.sql");
    private static final Pattern ROW =
            Pattern.compile("'([^']+@example\\.com)', '([A-Za-z0-9+/=]+)', '([A-Za-z0-9+/=]+)'");

    private SeedData() {
    }

    public static boolean exists() {
        return Files.isReadable(SEED);
    }

    public static List<SeedStudent> students() throws IOException {
        Matcher m = ROW.matcher(Files.readString(SEED));
        List<SeedStudent> out = new ArrayList<>();
        while (m.find()) {
            out.add(new SeedStudent(m.group(1), m.group(2), m.group(3)));
        }
        return out;
    }
}
