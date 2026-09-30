package com.smartstudy;

import com.smartstudy.config.AppConfig;
import com.smartstudy.controller.AttemptController;
import com.smartstudy.controller.AuthController;
import com.smartstudy.controller.HealthController;
import com.smartstudy.controller.PerformanceController;
import com.smartstudy.controller.QuizController;
import com.smartstudy.controller.TopicController;
import com.smartstudy.repository.Database;
import com.smartstudy.repository.JdbcPerformanceRepository;
import com.smartstudy.repository.JdbcQuestionRepository;
import com.smartstudy.repository.JdbcQuizAttemptRepository;
import com.smartstudy.repository.JdbcQuizRepository;
import com.smartstudy.repository.JdbcSessionRepository;
import com.smartstudy.repository.JdbcStudentRepository;
import com.smartstudy.repository.JdbcTopicRepository;
import com.smartstudy.repository.QuestionRepository;
import com.smartstudy.repository.QuizRepository;
import com.smartstudy.repository.TopicRepository;
import com.smartstudy.service.AttemptService;
import com.smartstudy.service.AuthService;
import com.smartstudy.service.PerformanceService;
import com.smartstudy.service.QuestionService;
import com.smartstudy.service.QuizService;
import com.smartstudy.service.TopicService;
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

        TopicRepository topicRepository = new JdbcTopicRepository(database);
        QuizRepository quizRepository = new JdbcQuizRepository(database);
        QuestionRepository questionRepository = new JdbcQuestionRepository(database);
        TopicService topicService = new TopicService(topicRepository);
        QuizService quizService = new QuizService(quizRepository);
        QuestionService questionService = new QuestionService(questionRepository, quizRepository, topicRepository);
        AttemptService attemptService =
                new AttemptService(quizRepository, questionRepository, new JdbcQuizAttemptRepository(database));

        PerformanceService performanceService = new PerformanceService(new JdbcPerformanceRepository(database),
                config.strongThreshold(), config.moderateThreshold());

        HttpServer server = createServer(config.serverHost(), config.serverPort(), authService,
                topicService, quizService, questionService, attemptService, performanceService);
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

    /** Health, authentication, quiz management (topics, quizzes, questions) and attempt submission. */
    public static HttpServer createServer(String host, int port, AuthService authService, TopicService topicService,
                                           QuizService quizService, QuestionService questionService,
                                           AttemptService attemptService) throws IOException {
        HttpServer server = createServer(host, port, authService);
        new TopicController(topicService, authService).mount(server);
        new QuizController(quizService, questionService, attemptService, authService).mount(server);
        new AttemptController(attemptService, authService).mount(server);
        return server;
    }

    /** Everything above plus performance analysis (topic study gaps). */
    public static HttpServer createServer(String host, int port, AuthService authService, TopicService topicService,
                                           QuizService quizService, QuestionService questionService,
                                           AttemptService attemptService, PerformanceService performanceService)
            throws IOException {
        HttpServer server = createServer(host, port, authService, topicService, quizService, questionService,
                attemptService);
        new PerformanceController(performanceService, authService).mount(server);
        return server;
    }
}
