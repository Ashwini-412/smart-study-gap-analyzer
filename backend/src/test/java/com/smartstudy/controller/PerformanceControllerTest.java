package com.smartstudy.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartstudy.App;
import com.smartstudy.service.AttemptService;
import com.smartstudy.service.AuthService;
import com.smartstudy.service.PerformanceService;
import com.smartstudy.service.QuestionService;
import com.smartstudy.service.QuizService;
import com.smartstudy.service.TopicService;
import com.smartstudy.support.InMemoryPerformanceRepository;
import com.smartstudy.support.InMemoryQuestionRepository;
import com.smartstudy.support.InMemoryQuizAttemptRepository;
import com.smartstudy.support.InMemoryQuizRepository;
import com.smartstudy.support.InMemorySessionRepository;
import com.smartstudy.support.InMemoryStudentRepository;
import com.smartstudy.support.InMemoryTopicRepository;
import com.smartstudy.util.PasswordHasher;
import com.smartstudy.util.TokenGenerator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GET /api/performance/gaps over real HTTP, with in-memory repositories (default thresholds 75/50). */
class PerformanceControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final String PASSWORD = "Sup3r-Secret-Pass";

    private static HttpServer server;
    private static String base;

    private String token;
    private long studentId;

    @BeforeAll
    static void start() throws Exception {
        AuthService authService = new AuthService(new InMemoryStudentRepository(), new InMemorySessionRepository(),
                new PasswordHasher(), new TokenGenerator(), Clock.systemUTC(), Duration.ofHours(24));
        InMemoryTopicRepository topics = new InMemoryTopicRepository();
        InMemoryQuizRepository quizzes = new InMemoryQuizRepository();
        InMemoryQuestionRepository questions = new InMemoryQuestionRepository();
        InMemoryQuizAttemptRepository attempts = new InMemoryQuizAttemptRepository();

        server = App.createServer("127.0.0.1", 0, authService, new TopicService(topics), new QuizService(quizzes),
                new QuestionService(questions, quizzes, topics), new AttemptService(quizzes, questions, attempts),
                new PerformanceService(new InMemoryPerformanceRepository(topics, questions, attempts), 75, 50));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @BeforeEach
    void freshStudentForEachTest() throws Exception {
        Object[] s = freshStudent();
        token = (String) s[0];
        studentId = (Long) s[1];
    }

    // ---- helpers ----

    private record Res(int status, String body) {
        JsonNode json() throws Exception {
            return JSON.readTree(body);
        }
    }

    /** A question as created: its id, and the ids of its correct and wrong option. */
    private record Q(long id, long correct, long wrong) {
    }

    private static Res call(String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body());
    }

    private static Object[] freshStudent() throws Exception {
        String email = "gaps-" + UUID.randomUUID() + "@example.com";
        call("POST", "/api/auth/register", JSON.createObjectNode().put("name", "Gap Tester").put("email", email)
                .put("password", PASSWORD).toString(), null);
        JsonNode login = call("POST", "/api/auth/login",
                JSON.createObjectNode().put("email", email).put("password", PASSWORD).toString(), null).json();
        return new Object[]{login.get("token").asText(), login.get("student").get("id").asLong()};
    }

    private long topic(String prefix) throws Exception {
        return call("POST", "/api/topics", JSON.createObjectNode().put("name", prefix + "-" + UUID.randomUUID())
                .toString(), token).json().get("id").asLong();
    }

    private long quiz() throws Exception {
        return call("POST", "/api/quizzes", JSON.createObjectNode().put("title", "Quiz-" + UUID.randomUUID())
                .toString(), token).json().get("id").asLong();
    }

    private Q question(long quizId, long topicId) throws Exception {
        ObjectNode n = JSON.createObjectNode().put("topicId", topicId).put("questionText", "Q " + UUID.randomUUID());
        ArrayNode opts = n.putArray("options");
        opts.addObject().put("text", "right").put("correct", true);
        opts.addObject().put("text", "wrong").put("correct", false);
        JsonNode created = call("POST", "/api/quizzes/" + quizId + "/questions", n.toString(), token).json();
        return new Q(created.get("id").asLong(), created.get("options").get(0).get("id").asLong(),
                created.get("options").get(1).get("id").asLong());
    }

    /** Submits {questionId, selectedOptionId} pairs as the given student. */
    private static void submit(String bearer, long quizId, long... pairs) throws Exception {
        ObjectNode n = JSON.createObjectNode();
        ArrayNode arr = n.putArray("answers");
        for (int i = 0; i < pairs.length; i += 2) {
            arr.addObject().put("questionId", pairs[i]).put("selectedOptionId", pairs[i + 1]);
        }
        assertEquals(201, call("POST", "/api/quizzes/" + quizId + "/attempts", n.toString(), bearer).status());
    }

    private JsonNode gapFor(long topicId, String bearer) throws Exception {
        for (JsonNode n : call("GET", "/api/performance/gaps", null, bearer).json()) {
            if (n.get("topicId").asLong() == topicId) {
                return n;
            }
        }
        throw new AssertionError("topic " + topicId + " missing from gaps");
    }

    private JsonNode gapFor(long topicId) throws Exception {
        return gapFor(topicId, token);
    }

    private static void assertGap(JsonNode gap, int total, int correct, String accuracy, String classification) {
        assertEquals(total, gap.get("totalQuestions").asInt(), "totalQuestions");
        assertEquals(correct, gap.get("correctCount").asInt(), "correctCount");
        assertEquals(0, new BigDecimal(accuracy).compareTo(gap.get("accuracyPercent").decimalValue()), "accuracy");
        assertEquals(classification, gap.get("classification").asText());
    }

    // ---- authentication ----

    @Test
    void gapsRequireAuthentication() throws Exception {
        assertEquals(401, call("GET", "/api/performance/gaps", null, null).status());
        assertEquals(401, call("GET", "/api/performance/gaps", null, "A".repeat(43)).status());
    }

    // ---- response shape ----

    @Test
    void authenticatedRequestListsTopicsWithExactlyTheDocumentedFields() throws Exception {
        long t = topic("Shape");
        Res r = call("GET", "/api/performance/gaps", null, token);
        assertEquals(200, r.status());
        assertTrue(r.json().isArray());
        JsonNode gap = gapFor(t);
        Set<String> names = new HashSet<>();
        gap.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("topicId", "topicName", "totalQuestions", "correctCount", "accuracyPercent",
                "classification"), names);
        String body = r.body().toLowerCase();
        for (String forbidden : List.of("studentid", "student_id", "attempt", "is_correct", "selectedoption",
                "password", "token")) {
            assertFalse(body.contains(forbidden), forbidden);
        }
    }

    // ---- no history ----

    @Test
    void topicWithNoHistoryIsNoDataWithNullAccuracy() throws Exception {
        long t = topic("Untouched");
        JsonNode gap = gapFor(t);
        assertEquals(0, gap.get("totalQuestions").asInt());
        assertEquals(0, gap.get("correctCount").asInt());
        assertTrue(gap.get("accuracyPercent").isNull());
        assertEquals("No Data", gap.get("classification").asText());
    }

    // ---- arithmetic and accumulation ----

    @Test
    void multipleAttemptsAccumulate() throws Exception {
        long t = topic("Accumulate");
        long qz = quiz();
        Q a = question(qz, t);
        Q b = question(qz, t);
        submit(token, qz, a.id(), a.correct(), b.id(), b.correct()); // 2/2
        submit(token, qz, a.id(), a.correct(), b.id(), b.wrong());   // 1/2
        assertGap(gapFor(t), 4, 3, "75.00", "Strong");
    }

    @Test
    void multipleQuizzesContributeToOneTopic() throws Exception {
        long t = topic("Shared");
        long quiz1 = quiz();
        long quiz2 = quiz();
        Q q1 = question(quiz1, t);
        Q q2 = question(quiz2, t);
        submit(token, quiz1, q1.id(), q1.correct());
        submit(token, quiz2, q2.id(), q2.wrong());
        assertGap(gapFor(t), 2, 1, "50.00", "Moderate");
    }

    @Test
    void unansweredQuestionsCountAsIncorrect() throws Exception {
        long t = topic("Unanswered");
        long qz = quiz();
        Q a = question(qz, t);
        question(qz, t); // never answered
        submit(token, qz, a.id(), a.correct());
        assertGap(gapFor(t), 2, 1, "50.00", "Moderate");
    }

    @Test
    void belowFiftyIsNeedsImprovement() throws Exception {
        long t = topic("Weak");
        long qz = quiz();
        Q a = question(qz, t);
        Q b = question(qz, t);
        Q c = question(qz, t);
        submit(token, qz, a.id(), a.correct(), b.id(), b.wrong(), c.id(), c.wrong());
        assertGap(gapFor(t), 3, 1, "33.33", "Needs Improvement");
    }

    @Test
    void topicsInOneQuizAreScoredSeparately() throws Exception {
        long strongTopic = topic("Separate-S");
        long weakTopic = topic("Separate-W");
        long qz = quiz();
        Q s = question(qz, strongTopic);
        Q w = question(qz, weakTopic);
        submit(token, qz, s.id(), s.correct(), w.id(), w.wrong());
        assertGap(gapFor(strongTopic), 1, 1, "100.00", "Strong");
        assertGap(gapFor(weakTopic), 1, 0, "0.00", "Needs Improvement");
    }

    // ---- ownership ----

    @Test
    void onlyTheCurrentStudentsHistoryContributes() throws Exception {
        long t = topic("Owner");
        long qz = quiz();
        Q a = question(qz, t);
        String other = (String) freshStudent()[0];
        submit(other, qz, a.id(), a.correct());
        submit(token, qz, a.id(), a.wrong());
        assertGap(gapFor(t), 1, 0, "0.00", "Needs Improvement");
        assertGap(gapFor(t, other), 1, 1, "100.00", "Strong");
    }

    @Test
    void studentIdInTheQueryStringIsIgnored() throws Exception {
        long t = topic("Query");
        long qz = quiz();
        Q a = question(qz, t);
        Object[] other = freshStudent();
        submit((String) other[0], qz, a.id(), a.correct());

        Res r = call("GET", "/api/performance/gaps?studentId=" + other[1], null, token);
        assertEquals(200, r.status());
        for (JsonNode n : r.json()) {
            if (n.get("topicId").asLong() == t) {
                assertEquals("No Data", n.get("classification").asText(), "the other student's attempt is not mine");
            }
        }
    }

    // ---- historical integrity ----

    @Test
    void laterQuizEditsDoNotRescoreHistory() throws Exception {
        long t = topic("History");
        long qz = quiz();
        Q a = question(qz, t);
        submit(token, qz, a.id(), a.correct());
        assertGap(gapFor(t), 1, 1, "100.00", "Strong");

        question(qz, t); // added after the attempt
        question(qz, t);

        assertGap(gapFor(t), 1, 1, "100.00", "Strong");
    }

    // ---- ordering ----

    @Test
    void topicsAreOrderedByName() throws Exception {
        String suffix = UUID.randomUUID().toString();
        long zeta = call("POST", "/api/topics", JSON.createObjectNode().put("name", "Zeta-" + suffix).toString(), token)
                .json().get("id").asLong();
        long alpha = call("POST", "/api/topics", JSON.createObjectNode().put("name", "Alpha-" + suffix).toString(), token)
                .json().get("id").asLong();

        List<String> names = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (JsonNode n : call("GET", "/api/performance/gaps", null, token).json()) {
            names.add(n.get("topicName").asText());
            ids.add(n.get("topicId").asLong());
        }
        assertTrue(ids.indexOf(alpha) < ids.indexOf(zeta));
        assertEquals(names.stream().sorted().toList(), names);
        assertEquals(call("GET", "/api/performance/gaps", null, token).body(),
                call("GET", "/api/performance/gaps", null, token).body(), "stable across calls");
    }

    // ---- routing ----

    @Test
    void onlyGetIsAllowed() throws Exception {
        for (String method : List.of("POST", "PUT", "DELETE")) {
            assertEquals(405, call(method, "/api/performance/gaps", method.equals("DELETE") ? null : "{}", token).status(),
                    method);
        }
        assertEquals(404, call("GET", "/api/performance/gaps/extra", null, token).status());
    }
}
