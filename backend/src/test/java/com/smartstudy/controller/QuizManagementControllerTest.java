package com.smartstudy.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstudy.App;
import com.smartstudy.service.AuthService;
import com.smartstudy.service.QuestionService;
import com.smartstudy.service.QuizService;
import com.smartstudy.service.TopicService;
import com.smartstudy.support.InMemoryQuestionRepository;
import com.smartstudy.support.InMemoryQuizRepository;
import com.smartstudy.support.InMemorySessionRepository;
import com.smartstudy.support.InMemoryStudentRepository;
import com.smartstudy.support.InMemoryTopicRepository;
import com.smartstudy.util.PasswordHasher;
import com.smartstudy.util.TokenGenerator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end over real HTTP: server + filter + controllers + services, with in-memory
 * repositories. Covers topics, quizzes and questions (Milestone 4).
 *
 * Note: the milestone brief asks POST /api/quizzes to "validate referenced topic exists", but
 * the schema (unchanged from Milestones 1-3) has no topic_id column on quizzes - a quiz reaches
 * topics only through its questions, by design (see docs/ARCHITECTURE.md section 6). So there is
 * no topic reference to validate at quiz-creation time; that bullet applies to question creation
 * instead, where topic_id is a real, required, FK-checked column, and is tested below.
 */
class QuizManagementControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static HttpServer server;
    private static String base;
    private static String token;

    @BeforeAll
    static void start() throws Exception {
        AuthService authService = new AuthService(new InMemoryStudentRepository(), new InMemorySessionRepository(),
                new PasswordHasher(), new TokenGenerator(), java.time.Clock.systemUTC(), Duration.ofHours(24));
        InMemoryTopicRepository topicRepo = new InMemoryTopicRepository();
        TopicService topicService = new TopicService(topicRepo);
        InMemoryQuizRepository quizRepo = new InMemoryQuizRepository();
        QuizService quizService = new QuizService(quizRepo);
        QuestionService questionService =
                new QuestionService(new InMemoryQuestionRepository(), quizRepo, topicRepo);

        server = App.createServer("127.0.0.1", 0, authService, topicService, quizService, questionService);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        String email = "quiz-mgmt-" + UUID.randomUUID() + "@example.com";
        call("POST", "/api/auth/register", regBody(email), null);
        token = call("POST", "/api/auth/login",
                JSON.createObjectNode().put("email", email).put("password", "Sup3r-Secret-Pass").toString(), null)
                .json().get("token").asText();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static String regBody(String email) {
        return JSON.createObjectNode().put("name", "Quiz Admin").put("email", email)
                .put("password", "Sup3r-Secret-Pass").toString();
    }

    private record Res(int status, String body, HttpResponse<String> raw) {
        JsonNode json() throws Exception {
            return JSON.readTree(body);
        }
    }

    private static Res call(String method, String path, String jsonBody, String bearerToken) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (jsonBody == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.method(method, HttpRequest.BodyPublishers.ofString(jsonBody)).header("Content-Type", "application/json");
        }
        if (bearerToken != null) {
            b.header("Authorization", "Bearer " + bearerToken);
        }
        HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body(), r);
    }

    private static Res auth(String method, String path, String jsonBody) throws Exception {
        return call(method, path, jsonBody, token);
    }

    private static void assertNoAuthSecrets(String body) {
        String lower = body.toLowerCase();
        for (String forbidden : List.of("password", "\"hash\"", "salt", "tokenhash", "token_hash")) {
            assertFalse(lower.contains(forbidden), "response must not mention '" + forbidden + "': " + body);
        }
    }

    // ---- authentication is required ----

    @Test
    void allEndpointsRequireAuthentication() throws Exception {
        assertEquals(401, call("GET", "/api/topics", null, null).status());
        assertEquals(401, call("POST", "/api/topics", "{}", null).status());
        assertEquals(401, call("GET", "/api/quizzes", null, null).status());
        assertEquals(401, call("POST", "/api/quizzes", "{}", null).status());
        assertEquals(401, call("GET", "/api/quizzes/1", null, null).status());
        assertEquals(401, call("GET", "/api/quizzes/1/questions", null, null).status());
        assertEquals(401, call("POST", "/api/quizzes/1/questions", "{}", null).status());
    }

    // ---- topics ----

    @Test
    void createAndListTopic() throws Exception {
        String name = "Topic-" + UUID.randomUUID();
        Res created = auth("POST", "/api/topics", JSON.createObjectNode().put("name", name).toString());
        assertEquals(201, created.status());
        assertEquals(name, created.json().get("name").asText());
        assertTrue(created.json().get("id").isNumber());

        Res list = auth("GET", "/api/topics", null);
        assertEquals(200, list.status());
        boolean found = false;
        for (JsonNode n : list.json()) {
            if (n.get("name").asText().equals(name)) {
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    void topicListIsDeterministicallyOrderedByName() throws Exception {
        Res r1 = auth("GET", "/api/topics", null);
        Res r2 = auth("GET", "/api/topics", null);
        assertEquals(r1.body(), r2.body());
    }

    @Test
    void createTopicMissingNameIsRejected() throws Exception {
        Res r = auth("POST", "/api/topics", "{}");
        assertEquals(400, r.status());
        assertTrue(r.json().get("fields").has("name"));
    }

    @Test
    void duplicateTopicNameIsRejectedWith409() throws Exception {
        String name = "Topic-" + UUID.randomUUID();
        assertEquals(201, auth("POST", "/api/topics", JSON.createObjectNode().put("name", name).toString()).status());
        Res dup = auth("POST", "/api/topics", JSON.createObjectNode().put("name", name).toString());
        assertEquals(409, dup.status());
    }

    @Test
    void topicSqlInjectionAttemptIsStoredAsPlainData() throws Exception {
        String evil = "Robert'); DROP TABLE topics;--";
        Res created = auth("POST", "/api/topics", JSON.createObjectNode().put("name", evil).toString());
        assertEquals(201, created.status());
        assertEquals(evil, created.json().get("name").asText());
        assertEquals(200, auth("GET", "/api/topics", null).status(), "table still usable afterwards");
    }

    // ---- quizzes ----

    @Test
    void createAndGetAndListQuiz() throws Exception {
        String title = "Quiz-" + UUID.randomUUID();
        Res created = auth("POST", "/api/quizzes",
                JSON.createObjectNode().put("title", title).put("description", "desc").toString());
        assertEquals(201, created.status());
        long id = created.json().get("id").asLong();
        assertEquals(title, created.json().get("title").asText());
        assertTrue(created.json().get("topics").isArray());

        Res got = auth("GET", "/api/quizzes/" + id, null);
        assertEquals(200, got.status());
        assertEquals(title, got.json().get("title").asText());

        Res list = auth("GET", "/api/quizzes", null);
        assertEquals(200, list.status());
        boolean found = false;
        for (JsonNode n : list.json()) {
            if (n.get("id").asLong() == id) {
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    void getNonexistentQuizIs404() throws Exception {
        assertEquals(404, auth("GET", "/api/quizzes/999999999", null).status());
    }

    @Test
    void getQuizWithNonNumericIdIs404() throws Exception {
        assertEquals(404, auth("GET", "/api/quizzes/not-a-number", null).status());
    }

    @Test
    void createQuizMissingTitleIsRejected() throws Exception {
        Res r = auth("POST", "/api/quizzes", "{}");
        assertEquals(400, r.status());
        assertTrue(r.json().get("fields").has("title"));
    }

    @Test
    void duplicateQuizTitleIsRejectedWith409() throws Exception {
        String title = "Quiz-" + UUID.randomUUID();
        assertEquals(201, auth("POST", "/api/quizzes", JSON.createObjectNode().put("title", title).toString()).status());
        Res dup = auth("POST", "/api/quizzes", JSON.createObjectNode().put("title", title).toString());
        assertEquals(409, dup.status());
    }

    // ---- questions ----

    private long createQuiz() throws Exception {
        Res r = auth("POST", "/api/quizzes",
                JSON.createObjectNode().put("title", "Quiz-" + UUID.randomUUID()).toString());
        return r.json().get("id").asLong();
    }

    private long createTopic() throws Exception {
        Res r = auth("POST", "/api/topics",
                JSON.createObjectNode().put("name", "Topic-" + UUID.randomUUID()).toString());
        return r.json().get("id").asLong();
    }

    private String questionBody(long topicId, String text, Object... optionTextCorrectPairs) {
        var node = JSON.createObjectNode();
        node.put("topicId", topicId);
        node.put("questionText", text);
        var options = node.putArray("options");
        for (int i = 0; i < optionTextCorrectPairs.length; i += 2) {
            var opt = options.addObject();
            opt.put("text", (String) optionTextCorrectPairs[i]);
            opt.put("correct", (Boolean) optionTextCorrectPairs[i + 1]);
        }
        return node.toString();
    }

    @Test
    void listQuestionsForNonexistentQuizIs404() throws Exception {
        assertEquals(404, auth("GET", "/api/quizzes/999999999/questions", null).status());
    }

    @Test
    void createQuestionForNonexistentQuizIs404() throws Exception {
        long topicId = createTopic();
        Res r = auth("POST", "/api/quizzes/999999999/questions",
                questionBody(topicId, "Text", "a", true, "b", false));
        assertEquals(404, r.status());
    }

    @Test
    void createQuestionWithOptionsThenRetrieveAndVerify() throws Exception {
        long quizId = createQuiz();
        long topicId = createTopic();

        Res created = auth("POST", "/api/quizzes/" + quizId + "/questions",
                questionBody(topicId, "What is 2 + 2?", "3", false, "4", true, "5", false));
        assertEquals(201, created.status());
        JsonNode createdJson = created.json();
        assertEquals(quizId, createdJson.get("quizId").asLong());
        assertEquals(topicId, createdJson.get("topicId").asLong());
        assertEquals(1, createdJson.get("position").asInt());
        assertEquals(3, createdJson.get("options").size());
        // The creation response is the administrative representation: it may show "correct".
        int correctCount = 0;
        for (JsonNode o : createdJson.get("options")) {
            if (o.get("correct").asBoolean()) {
                correctCount++;
            }
        }
        assertEquals(1, correctCount);

        Res list = auth("GET", "/api/quizzes/" + quizId + "/questions", null);
        assertEquals(200, list.status());
        assertEquals(1, list.json().size());
        JsonNode listed = list.json().get(0);
        assertEquals("What is 2 + 2?", listed.get("questionText").asText());
        assertEquals(3, listed.get("options").size());
        // The quiz-taking representation must never expose which option is correct.
        assertFalse(list.body().contains("\"correct\""), "listing must not expose the answer key");
        for (JsonNode o : listed.get("options")) {
            assertFalse(o.has("correct"));
        }
    }

    @Test
    void questionsAndOptionsPreserveOrdering() throws Exception {
        long quizId = createQuiz();
        long topicId = createTopic();
        auth("POST", "/api/quizzes/" + quizId + "/questions", questionBody(topicId, "Q1", "z", false, "a", true));
        auth("POST", "/api/quizzes/" + quizId + "/questions", questionBody(topicId, "Q2", "y", true, "b", false));

        Res list = auth("GET", "/api/quizzes/" + quizId + "/questions", null);
        assertEquals("Q1", list.json().get(0).get("questionText").asText());
        assertEquals(1, list.json().get(0).get("position").asInt());
        assertEquals("Q2", list.json().get(1).get("questionText").asText());
        assertEquals(2, list.json().get(1).get("position").asInt());
        assertEquals("z", list.json().get(0).get("options").get(0).get("text").asText());
        assertEquals("a", list.json().get(0).get("options").get(1).get("text").asText());
    }

    @Test
    void createQuestionMissingTextIsRejected() throws Exception {
        long quizId = createQuiz();
        long topicId = createTopic();
        Res r = auth("POST", "/api/quizzes/" + quizId + "/questions",
                questionBody(topicId, "", "a", true, "b", false));
        assertEquals(400, r.status());
        assertTrue(r.json().get("fields").has("questionText"));
    }

    @Test
    void createQuestionWithUnknownTopicIsRejected() throws Exception {
        long quizId = createQuiz();
        Res r = auth("POST", "/api/quizzes/" + quizId + "/questions",
                questionBody(999_999_999L, "Text", "a", true, "b", false));
        assertEquals(400, r.status());
        assertTrue(r.json().get("fields").has("topicId"));
    }

    @Test
    void createQuestionWithTooFewOptionsIsRejected() throws Exception {
        long quizId = createQuiz();
        long topicId = createTopic();
        Res r = auth("POST", "/api/quizzes/" + quizId + "/questions", questionBody(topicId, "Text", "only", true));
        assertEquals(400, r.status());
        assertTrue(r.json().get("fields").has("options"));
    }

    @Test
    void createQuestionWithNoCorrectOptionIsRejected() throws Exception {
        long quizId = createQuiz();
        long topicId = createTopic();
        Res r = auth("POST", "/api/quizzes/" + quizId + "/questions",
                questionBody(topicId, "Text", "a", false, "b", false));
        assertEquals(400, r.status());
        assertTrue(r.json().get("fields").has("options"));
    }

    @Test
    void createQuestionWithTwoCorrectOptionsIsRejected() throws Exception {
        long quizId = createQuiz();
        long topicId = createTopic();
        Res r = auth("POST", "/api/quizzes/" + quizId + "/questions",
                questionBody(topicId, "Text", "a", true, "b", true));
        assertEquals(400, r.status());
        assertTrue(r.json().get("fields").has("options"));
    }

    @Test
    void createQuestionWithBlankOptionTextIsRejected() throws Exception {
        long quizId = createQuiz();
        long topicId = createTopic();
        Res r = auth("POST", "/api/quizzes/" + quizId + "/questions",
                questionBody(topicId, "Text", "  ", true, "b", false));
        assertEquals(400, r.status());
    }

    @Test
    void questionSqlInjectionAttemptIsStoredAsPlainData() throws Exception {
        long quizId = createQuiz();
        long topicId = createTopic();
        String evil = "Robert'); DROP TABLE questions;--";
        Res created = auth("POST", "/api/quizzes/" + quizId + "/questions",
                questionBody(topicId, evil, "a", true, "b", false));
        assertEquals(201, created.status());
        assertEquals(evil, created.json().get("questionText").asText());
        assertEquals(200, auth("GET", "/api/quizzes/" + quizId + "/questions", null).status(), "table still usable");
    }

    // ---- routing ----

    @Test
    void wrongMethodsAreRejectedWith405() throws Exception {
        assertEquals(405, auth("DELETE", "/api/topics", null).status());
        assertEquals(405, auth("DELETE", "/api/quizzes", null).status());
        long quizId = createQuiz();
        assertEquals(405, auth("POST", "/api/quizzes/" + quizId, "{}").status());
        assertEquals(405, auth("DELETE", "/api/quizzes/" + quizId + "/questions", null).status());
    }

    @Test
    void unknownSubPathsAreNotFound() throws Exception {
        assertEquals(404, auth("GET", "/api/topics/extra", null).status());
        long quizId = createQuiz();
        assertEquals(404, auth("GET", "/api/quizzes/" + quizId + "/extra", null).status());
        assertEquals(404, auth("GET", "/api/quizzes/" + quizId + "/questions/extra", null).status());
    }

    @Test
    void noResponseEverLeaksAuthenticationSecrets() throws Exception {
        long quizId = createQuiz();
        long topicId = createTopic();
        assertNoAuthSecrets(auth("GET", "/api/topics", null).body());
        assertNoAuthSecrets(auth("GET", "/api/quizzes", null).body());
        assertNoAuthSecrets(auth("GET", "/api/quizzes/" + quizId, null).body());
        assertNoAuthSecrets(auth("GET", "/api/quizzes/" + quizId + "/questions", null).body());
        assertNoAuthSecrets(auth("POST", "/api/quizzes/" + quizId + "/questions",
                questionBody(topicId, "Text", "a", true, "b", false)).body());
    }

    // ---- M8: malformed input must be a 400, never a 500 ----

    @Test
    void malformedJsonBodiesAreRejectedWith400OnEveryCreateEndpoint() throws Exception {
        long quizId = createQuiz();
        for (String path : List.of("/api/topics", "/api/quizzes", "/api/quizzes/" + quizId + "/questions")) {
            for (String body : List.of("{not json", "", "null", "[]")) {
                assertEquals(400, auth("POST", path, body).status(), path + " body=" + body);
            }
        }
    }

    @Test
    void wrongJsonTypesAreRejectedWith400() throws Exception {
        long quizId = createQuiz();
        long topicId = createTopic();
        String path = "/api/quizzes/" + quizId + "/questions";
        assertEquals(400, auth("POST", path,
                "{\"topicId\":" + topicId + ",\"questionText\":\"T\",\"options\":\"not-an-array\"}").status());
        assertEquals(400, auth("POST", path,
                "{\"topicId\":\"abc\",\"questionText\":\"T\",\"options\":[]}").status());
        assertEquals(400, auth("POST", path,
                "{\"topicId\":" + topicId + ",\"questionText\":\"T\",\"options\":[\"a\",\"b\"]}").status());
    }

    @Test
    void nullOptionElementIsRejectedWith400() throws Exception {
        long quizId = createQuiz();
        long topicId = createTopic();
        Res r = auth("POST", "/api/quizzes/" + quizId + "/questions",
                "{\"topicId\":" + topicId + ",\"questionText\":\"T\",\"options\":[null,{\"text\":\"b\",\"correct\":true}]}");
        assertEquals(400, r.status());
        assertTrue(r.json().get("fields").has("options[0]"));
        assertEquals(0, auth("GET", "/api/quizzes/" + quizId + "/questions", null).json().size(),
                "nothing persisted");
    }

    // ---- M8: clients cannot supply server-owned fields ----

    @Test
    void clientSuppliedStudentIdIsRejectedNotSilentlyUsed() throws Exception {
        Res r = auth("POST", "/api/topics",
                JSON.createObjectNode().put("name", "Topic-" + UUID.randomUUID()).put("studentId", 1).toString());
        assertEquals(400, r.status());
    }

    @Test
    void clientSuppliedPositionOrIdIsRejectedNotSilentlyUsed() throws Exception {
        long quizId = createQuiz();
        long topicId = createTopic();
        String path = "/api/quizzes/" + quizId + "/questions";
        assertEquals(400, auth("POST", path, "{\"topicId\":" + topicId + ",\"questionText\":\"T\",\"position\":99,"
                + "\"options\":[{\"text\":\"a\",\"correct\":true},{\"text\":\"b\",\"correct\":false}]}").status());
        assertEquals(400, auth("POST", "/api/quizzes",
                "{\"id\":42,\"title\":\"Quiz-" + UUID.randomUUID() + "\"}").status());
    }

    // ---- M8: option-count boundaries at the HTTP level ----

    @Test
    void sixOptionsAreAcceptedAndSevenRejected() throws Exception {
        long quizId = createQuiz();
        long topicId = createTopic();
        String path = "/api/quizzes/" + quizId + "/questions";
        assertEquals(201, auth("POST", path, questionBody(topicId, "Six",
                "a", true, "b", false, "c", false, "d", false, "e", false, "f", false)).status());
        assertEquals(400, auth("POST", path, questionBody(topicId, "Seven",
                "a", true, "b", false, "c", false, "d", false, "e", false, "f", false, "g", false)).status());
    }

    // ---- M8: deterministic ordering, asserted rather than just "same twice" ----

    @Test
    void topicsAreListedAlphabetically() throws Exception {
        String suffix = UUID.randomUUID().toString();
        auth("POST", "/api/topics", JSON.createObjectNode().put("name", "Zeta-" + suffix).toString());
        auth("POST", "/api/topics", JSON.createObjectNode().put("name", "Alpha-" + suffix).toString());
        List<String> names = new java.util.ArrayList<>();
        for (JsonNode n : auth("GET", "/api/topics", null).json()) {
            names.add(n.get("name").asText());
        }
        assertTrue(names.indexOf("Alpha-" + suffix) < names.indexOf("Zeta-" + suffix));
        assertEquals(names.stream().sorted().toList(), names);
    }

    @Test
    void quizzesAreListedInCreationOrder() throws Exception {
        long first = createQuiz();
        long second = createQuiz();
        List<Long> ids = new java.util.ArrayList<>();
        for (JsonNode n : auth("GET", "/api/quizzes", null).json()) {
            ids.add(n.get("id").asLong());
        }
        assertTrue(ids.indexOf(first) < ids.indexOf(second));
        assertEquals(ids.stream().sorted().toList(), ids);
    }

    // ---- M8: quiz-taking response shape exposes only what quiz-taking needs ----

    @Test
    void questionListingExposesOnlyQuizTakingFields() throws Exception {
        long quizId = createQuiz();
        long topicId = createTopic();
        auth("POST", "/api/quizzes/" + quizId + "/questions", questionBody(topicId, "Q", "a", true, "b", false));
        JsonNode q = auth("GET", "/api/quizzes/" + quizId + "/questions", null).json().get(0);
        assertEquals(java.util.Set.of("id", "questionText", "position", "options"), fieldNames(q));
        assertEquals(java.util.Set.of("id", "text"), fieldNames(q.get("options").get(0)));
    }

    private static java.util.Set<String> fieldNames(JsonNode node) {
        java.util.Set<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
