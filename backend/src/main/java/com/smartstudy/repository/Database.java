package com.smartstudy.repository;

import com.smartstudy.config.AppConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Single place that opens JDBC connections. Repositories receive a Database
 * and use try-with-resources around the returned Connection.
 */
public class Database {

    private final String url;
    private final String username;
    private final String password;

    static {
        // Register the driver explicitly. DriverManager's automatic discovery does not see it
        // when the app runs under an isolated classloader (e.g. `mvn exec:java`).
        try {
            Class.forName("com.mysql.cj.jdbc.Driver", true, Database.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError("MySQL JDBC driver (mysql-connector-j) is not on the classpath");
        }
    }

    public Database(AppConfig config) {
        // Pin the connection to UTC so TIMESTAMP columns (session expiry) round-trip
        // as the same instant regardless of the JVM's or the server's time zone.
        this.url = config.jdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        this.username = config.dbUsername();
        this.password = config.dbPassword();
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }
}
