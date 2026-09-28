package com.smartstudy;

import com.smartstudy.config.AppConfig;
import com.smartstudy.controller.HealthController;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class App {

    public static void main(String[] args) throws IOException {
        AppConfig config = AppConfig.load();
        HttpServer server = createServer(config.serverHost(), config.serverPort());
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(1)));
        System.out.println("Smart Study Gap Analyzer listening on http://" + config.serverHost() + ":"
                + server.getAddress().getPort());
    }

    /** Builds (but does not start) the server. Port 0 picks a free port, which tests use. */
    public static HttpServer createServer(String host, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext(HealthController.PATH, new HealthController());
        return server;
    }
}
