package com.smartstudy;

import com.smartstudy.config.AppConfig;
import com.smartstudy.controller.AuthController;
import com.smartstudy.controller.HealthController;
import com.smartstudy.repository.Database;
import com.smartstudy.repository.JdbcSessionRepository;
import com.smartstudy.repository.JdbcStudentRepository;
import com.smartstudy.service.AuthService;
import com.smartstudy.util.PasswordHasher;
import com.smartstudy.util.TokenGenerator;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;

public class App {

    public static void main(String[] args) throws IOException {
        AppConfig config = AppConfig.load();
        if (config.dbPassword().isBlank()) {
            System.err.println("WARNING: DB_PASSWORD is not set; endpoints that need the database will return 500.");
        }

        Database database = new Database(config);
        AuthService authService = new AuthService(
                new JdbcStudentRepository(database),
                new JdbcSessionRepository(database),
                new PasswordHasher(),
                new TokenGenerator(),
                Clock.systemUTC(),
                Duration.ofHours(config.sessionHours()));

        HttpServer server = createServer(config.serverHost(), config.serverPort(), authService);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(1)));
        System.out.println("Smart Study Gap Analyzer listening on http://" + config.serverHost() + ":"
                + server.getAddress().getPort());
    }

    /** Health endpoint only (no database needed). Port 0 picks a free port, which tests use. */
    public static HttpServer createServer(String host, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext(HealthController.PATH, new HealthController());
        return server;
    }

    /** Health plus the authentication endpoints. */
    public static HttpServer createServer(String host, int port, AuthService authService) throws IOException {
        HttpServer server = createServer(host, port);
        new AuthController(authService).mount(server);
        return server;
    }
}
