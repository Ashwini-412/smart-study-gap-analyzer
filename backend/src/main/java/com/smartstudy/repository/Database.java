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

    public Database(AppConfig config) {
        this.url = config.jdbcUrl();
        this.username = config.dbUsername();
        this.password = config.dbPassword();
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }
}
