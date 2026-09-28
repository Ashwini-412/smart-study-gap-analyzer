package com.smartstudy.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Application settings loaded from application.properties.
 * Property values may reference environment variables as ${NAME:default}.
 */
public final class AppConfig {

    private static final String RESOURCE = "application.properties";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)(?::([^}]*))?}");

    private final String serverHost;
    private final int serverPort;
    private final String dbHost;
    private final int dbPort;
    private final String dbName;
    private final String dbUsername;
    private final String dbPassword;
    private final double strongThreshold;
    private final double moderateThreshold;

    private AppConfig(Properties p, Function<String, String> env) {
        this.serverHost = resolve(p, "server.host", env);
        this.serverPort = parsePort(resolve(p, "server.port", env), "server.port");
        this.dbHost = resolve(p, "db.host", env);
        this.dbPort = parsePort(resolve(p, "db.port", env), "db.port");
        this.dbName = resolve(p, "db.name", env);
        this.dbUsername = resolve(p, "db.username", env);
        this.dbPassword = resolve(p, "db.password", env);
        this.strongThreshold = parsePercent(resolve(p, "gap.threshold.strong", env), "gap.threshold.strong");
        this.moderateThreshold = parsePercent(resolve(p, "gap.threshold.moderate", env), "gap.threshold.moderate");

        if (moderateThreshold >= strongThreshold) {
            throw new IllegalStateException(
                    "gap.threshold.moderate (" + moderateThreshold + ") must be less than gap.threshold.strong ("
                            + strongThreshold + ")");
        }
        if (dbHost.isBlank() || dbName.isBlank() || dbUsername.isBlank()) {
            throw new IllegalStateException("db.host, db.name and db.username must not be blank");
        }
    }

    /** Loads application.properties from the classpath, using the real process environment. */
    public static AppConfig load() {
        try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " not found on classpath");
            }
            return load(in, System::getenv);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + RESOURCE, e);
        }
    }

    /** Loads from any stream with an injectable environment (used by tests). */
    public static AppConfig load(InputStream in, Function<String, String> env) throws IOException {
        Properties p = new Properties();
        p.load(in);
        return new AppConfig(p, env);
    }

    private static String resolve(Properties p, String key, Function<String, String> env) {
        String raw = p.getProperty(key);
        if (raw == null) {
            throw new IllegalStateException("Missing required property: " + key);
        }
        Matcher m = PLACEHOLDER.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String fromEnv = env.apply(m.group(1));
            String value = fromEnv != null ? fromEnv : m.group(2);
            if (value == null) {
                throw new IllegalStateException(
                        "Property " + key + " needs environment variable " + m.group(1) + " (no default)");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString().trim();
    }

    private static int parsePort(String value, String key) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) {
                throw new IllegalStateException(key + " must be between 1 and 65535, got " + value);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalStateException(key + " must be an integer, got '" + value + "'");
        }
    }

    private static double parsePercent(String value, String key) {
        try {
            double d = Double.parseDouble(value);
            if (d < 0 || d > 100) {
                throw new IllegalStateException(key + " must be between 0 and 100, got " + value);
            }
            return d;
        } catch (NumberFormatException e) {
            throw new IllegalStateException(key + " must be a number, got '" + value + "'");
        }
    }

    public String serverHost() {
        return serverHost;
    }

    public int serverPort() {
        return serverPort;
    }

    public String dbHost() {
        return dbHost;
    }

    public int dbPort() {
        return dbPort;
    }

    public String dbName() {
        return dbName;
    }

    public String dbUsername() {
        return dbUsername;
    }

    public String dbPassword() {
        return dbPassword;
    }

    public double strongThreshold() {
        return strongThreshold;
    }

    public double moderateThreshold() {
        return moderateThreshold;
    }

    /** JDBC URL without credentials. */
    public String jdbcUrl() {
        return "jdbc:mysql://" + dbHost + ":" + dbPort + "/" + dbName;
    }

    @Override
    public String toString() {
        // Deliberately omits the password.
        return "AppConfig[server=" + serverHost + ":" + serverPort + ", db=" + dbUsername + "@" + jdbcUrl()
                + ", strong=" + strongThreshold + ", moderate=" + moderateThreshold + "]";
    }
}
