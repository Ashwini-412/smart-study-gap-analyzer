package com.smartstudy.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartstudy.App;
import com.smartstudy.model.AttemptAnswer;
import com.smartstudy.model.QuizAttempt;
import com.smartstudy.repository.DataAccessException;
import com.smartstudy.service.AttemptService;
import com.smartstudy.service.AuthService;
import com.smartstudy.service.QuestionService;
import com.smartstudy.service.QuizService;
import com.smartstudy.service.TopicService;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The attempt workflow over real HTTP, with in-memory repositories: submission
 * (POST /api/quizzes/{id}/attempts, Milestone 9) and result retrieval (GET /api/attempts/{id},
 * Milestone 10).
 */
class AttemptSubmissionControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final String PASSWORD = "Sup3r-Secret-Pass";

    private static HttpServer server;
    private static String base;
    private static InMemoryQuizAttemptRepository attempts;

    private String token;
    private long studentId;
    private long quizId;
    private long questionAId;
    private long correctA;
    private long wrongA;
    private long questionBId;
    private long correctB;
    private long wrongB;

    @BeforeAll
    static void start() throws Exception {
        AuthService authService = new AuthService(new InMemoryStudentRepository(), new InMemorySessionRepository(),
                new PasswordHasher(), new TokenGenerator(), Clock.systemUTC(), Duration.ofHours(24));
        InMemoryTopicRepository topics = new InMemoryTopicRepository();
        InMemoryQuizRepository quizzes = new InMemoryQuizRepository();
        InMemoryQuestionRepository questions = new InMemoryQuestionRepository();
        attempts = new InMemoryQuizAttemptRepository();

        server = App.createServer("127.0.0.1", 0, authService, new TopicService(topics), new QuizService(quizzes),
                new QuestionService(questions, quizzes, topics), new AttemptService(quizzes, questions, attempts));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private record Res(int status, String body) {
        JsonNode json() throws Exception {
            return JSON.readTree(body);
        }
    }

    private static Res call(String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body());
    }

    /** Registers a fresh student; returns {token, studentId}. */
    private static Object[] freshStudent() throws Exception {
        String email = "attempt-" + UUID.randomUUID() + "@example.com";
        call("POST", "/api/auth/register", JSON.createObjectNode().put("name", "Attempt Tester")
                .put("email", email).put("password", PASSWORD).toString(), null);
        JsonNode login = call("POST", "/api/auth/login",
                JSON.createObjectNode().put("email", email).put("password", PASSWORD).toString(), null).json();
        return new Object[]{login.get("token").asText(), login.get("student").get("id").asLong()};
    }

    @BeforeEach
    void setUpQuiz() throws Exception {
        Object[] s = freshStudent();
        token = (String) s[0];
        studentId = (Long) s[1];

        quizId = call("POST", "/api/quizzes", JSON.createObjectNode()
                .put("title", "Quiz-" + UUID.randomUUID()).toString(), token).json().get("id").asLong();
        long topicId = call("POST", "/api/topics", JSON.createObjectNode()
                .put("name", "Topic-" + UUID.randomUUID()).toString(), token).json().get("id").asLong();

        JsonNode qa = createQuestion(topicId, "2 + 2 = ?", "3", false, "4", true);
        questionAId = qa.get("id").asLong();
        wrongA = qa.get("options").get(0).get("id").asLong();
        correctA = qa.get("options").get(1).get("id").asLong();
        JsonNode qb = createQuestion(topicId, "3 + 3 = ?", "6", true, "5", false);
        questionBId = qb.get("id").asLong();
        correctB = qb.get("options").get(0).get("id").asLong();
        wrongB = qb.get("options").get(1).get("id").asLong();
    }

    private JsonNode createQuestion(long topicId, String text, Object... pairs) throws Exception {
        ObjectNode n = JSON.createObjectNode().put("topicId", topicId).put("questionText", text);
        ArrayNode opts = n.putArray("options");
        for (int i = 0; i < pairs.length; i += 2) {
            opts.addObject().put("text", (String) pairs[i]).put("correct", (Boolean) pairs[i + 1]);
        }
        return call("POST", "/api/quizzes/" + quizId + "/questions", n.toString(), token).json();
    }

    private static String answers(long... questionOptionPairs) {
        ObjectNode n = JSON.createObjectNode();
        ArrayNode arr = n.putArray("answers");
        for (int i = 0; i < questionOptionPairs.length; i += 2) {
            arr.addObject().put("questionId", questionOptionPairs[i]).put("selectedOptionId", questionOptionPairs[i + 1]);
        }
        return n.toString();
    }

    private Res submit(String body) throws Exception {
        return call("POST", "/api/quizzes/" + quizId + "/attempts", body, token);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // ---- authentication ----

    @Test
    void unauthenticatedSubmissionIsRejected() throws Exception {
        int before = attempts.attemptCount();
        assertEquals(401, call("POST", "/api/quizzes/" + quizId + "/attempts", answers(questionAId, correctA), null).status());
        assertEquals(401, call("POST", "/api/quizzes/" + quizId + "/attempts", answers(questionAId, correctA),
                "A".repeat(43)).status());
        assertEquals(before, attempts.attemptCount());
    }

    // ---- success ----

    @Test
    void validSubmissionIsStoredAndReturns201() throws Exception {
        Res r = submit(answers(questionAId, correctA, questionBId, correctB));
        assertEquals(201, r.status());
        JsonNode j = r.json();
        assertEquals(Set.of("id", "quizId", "submittedAt", "totalQuestions", "answeredCount"), fieldNames(j));
        assertEquals(quizId, j.get("quizId").asLong());
        assertEquals(2, j.get("totalQuestions").asInt());
        assertEquals(2, j.get("answeredCount").asInt());

        QuizAttempt stored = attempts.findById(j.get("id").asLong()).orElseThrow();
        assertEquals(quizId, stored.quizId());
        assertEquals(2, stored.correctCount(), "evaluated on the server from the answer key");
        List<AttemptAnswer> rows = attempts.findAnswersByAttemptId(stored.id());
        assertEquals(2, rows.size());
        assertEquals(correctA, rows.get(0).selectedOptionId());
        assertEquals(correctB, rows.get(1).selectedOptionId());
    }

    @Test
    void studentIdentityComesFromTheToken() throws Exception {
        Object[] other = freshStudent();
        long otherId = (Long) other[1];

        long attemptId = call("POST", "/api/quizzes/" + quizId + "/attempts", answers(questionAId, correctA),
                (String) other[0]).json().get("id").asLong();

        assertEquals(otherId, attempts.findById(attemptId).orElseThrow().studentId());
        assertTrue(attempts.findByStudentId(studentId).stream().noneMatch(a -> a.id() == attemptId),
                "stored under the token owner only");
    }

    @Test
    void unansweredQuestionsAreStoredAsUnanswered() throws Exception {
        Res r = submit(answers(questionAId, correctA));
        assertEquals(201, r.status());
        assertEquals(2, r.json().get("totalQuestions").asInt());
        assertEquals(1, r.json().get("answeredCount").asInt());
        AttemptAnswer b = attempts.findAnswersByAttemptId(r.json().get("id").asLong()).get(1);
        assertEquals(questionBId, b.questionId());
        assertNull(b.selectedOptionId());
        assertFalse(b.correct());
    }

    // ---- client cannot supply identity or evaluation ----

    @Test
    void clientStudentIdIsRejected() throws Exception {
        int before = attempts.attemptCount();
        ObjectNode body = (ObjectNode) JSON.readTree(answers(questionAId, correctA));
        body.put("studentId", studentId + 1);
        assertEquals(400, submit(body.toString()).status());
        assertEquals(before, attempts.attemptCount());
    }

    @Test
    void clientScoreCorrectCountAndCorrectnessAreRejected() throws Exception {
        int before = attempts.attemptCount();
        for (String field : List.of("score", "scorePercent", "correctCount", "totalQuestions")) {
            ObjectNode body = (ObjectNode) JSON.readTree(answers(questionAId, wrongA));
            body.put(field, 100);
            assertEquals(400, submit(body.toString()).status(), field);
        }
        String perAnswer = "{\"answers\":[{\"questionId\":" + questionAId + ",\"selectedOptionId\":" + wrongA
                + ",\"correct\":true}]}";
        assertEquals(400, submit(perAnswer).status(), "per-answer correctness");
        assertEquals(before, attempts.attemptCount());
    }

    @Test
    void submissionResponseNeverLeaksTheAnswerKeyOrScore() throws Exception {
        String body = submit(answers(questionAId, wrongA, questionBId, correctB)).body().toLowerCase();
        for (String forbidden : List.of("correct", "score", "iscorrect", "is_correct")) {
            assertFalse(body.contains(forbidden), "response must not contain '" + forbidden + "': " + body);
        }
    }

    // ---- validation ----

    @Test
    void unknownQuizIs404() throws Exception {
        assertEquals(404, call("POST", "/api/quizzes/999999999/attempts", answers(questionAId, correctA), token).status());
        assertEquals(404, call("POST", "/api/quizzes/abc/attempts", answers(questionAId, correctA), token).status());
    }

    @Test
    void emptyOrMissingAnswersIs400() throws Exception {
        assertTrue(submit("{\"answers\":[]}").json().get("fields").has("answers"));
        assertEquals(400, submit("{}").status());
    }

    @Test
    void duplicateQuestionIs400() throws Exception {
        Res r = submit(answers(questionAId, correctA, questionAId, wrongA));
        assertEquals(400, r.status());
        assertTrue(r.json().get("fields").has("answers[1].questionId"));
    }

    @Test
    void questionFromAnotherQuizIs400() throws Exception {
        long otherQuiz = call("POST", "/api/quizzes", JSON.createObjectNode()
                .put("title", "Quiz-" + UUID.randomUUID()).toString(), token).json().get("id").asLong();
        Res r = call("POST", "/api/quizzes/" + otherQuiz + "/attempts", answers(questionAId, correctA), token);
        assertEquals(400, r.status());
        assertTrue(r.json().get("fields").has("answers[0].questionId"));
    }

    @Test
    void optionFromAnotherQuestionIs400() throws Exception {
        Res r = submit(answers(questionAId, correctB));
        assertEquals(400, r.status());
        assertTrue(r.json().get("fields").has("answers[0].selectedOptionId"));
    }

    @Test
    void nullAnswerElementIs400NotA500() throws Exception {
        Res r = submit("{\"answers\":[null]}");
        assertEquals(400, r.status());
        assertTrue(r.json().get("fields").has("answers[0]"));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        for (String body : List.of("{not json", "", "null", "[]", "{\"answers\":\"x\"}",
                "{\"answers\":[{\"questionId\":\"abc\"}]}")) {
            assertEquals(400, submit(body).status(), "body=" + body);
        }
    }

    @Test
    void invalidSubmissionsPersistNothing() throws Exception {
        int before = attempts.attemptCount();
        submit(answers(questionAId, correctA, questionAId, wrongA));
        submit(answers(questionAId, correctB));
        submit("{\"answers\":[null]}");
        assertEquals(before, attempts.attemptCount());
    }

    // ---- persistence failure ----

    @Test
    void persistenceFailureIs500AndLeavesNoPartialAttempt() throws Exception {
        int before = attempts.attemptCount();
        attempts.failNextWrite(new DataAccessException("simulated answer insert failure", new SQLException("boom")));
        Res r = submit(answers(questionAId, correctA));
        assertEquals(500, r.status());
        assertFalse(r.body().contains("boom"), "internal error detail must not reach the client");
        assertEquals(before, attempts.attemptCount());
    }

    // ---- routing ----

    @Test
    void onlyPostIsAllowed() throws Exception {
        assertEquals(405, call("GET", "/api/quizzes/" + quizId + "/attempts", null, token).status());
        assertEquals(404, call("POST", "/api/quizzes/" + quizId + "/attempts/extra", answers(questionAId, correctA),
                token).status());
    }

    // ==== Milestone 10: GET /api/attempts/{id} ====

    private long submitted(String bearer, long... pairs) throws Exception {
        return call("POST", "/api/quizzes/" + quizId + "/attempts", answers(pairs), bearer).json().get("id").asLong();
    }

    private Res result(long attemptId, String bearer) throws Exception {
        return call("GET", "/api/attempts/" + attemptId, null, bearer);
    }

    @Test
    void ownerRetrievesTheStoredResult() throws Exception {
        long id = submitted(token, questionAId, correctA, questionBId, wrongB);
        Res r = result(id, token);
        assertEquals(200, r.status());
        JsonNode j = r.json();
        assertEquals(Set.of("id", "quizId", "submittedAt", "totalQuestions", "answeredCount", "correctCount",
                "scorePercent", "answers"), fieldNames(j));
        assertEquals(id, j.get("id").asLong());
        assertEquals(quizId, j.get("quizId").asLong());
        assertEquals(2, j.get("totalQuestions").asInt());
        assertEquals(2, j.get("answeredCount").asInt());
        assertEquals(1, j.get("correctCount").asInt());
        assertEquals(0, new java.math.BigDecimal("50.00").compareTo(j.get("scorePercent").decimalValue()));

        JsonNode answers = j.get("answers");
        assertEquals(2, answers.size());
        assertEquals(questionAId, answers.get(0).get("questionId").asLong());
        assertEquals(correctA, answers.get(0).get("selectedOptionId").asLong());
        assertTrue(answers.get(0).get("correct").asBoolean());
        assertEquals(questionBId, answers.get(1).get("questionId").asLong());
        assertFalse(answers.get(1).get("correct").asBoolean());
    }

    @Test
    void resultMatchesWhatWasPersistedAtSubmission() throws Exception {
        long id = submitted(token, questionAId, wrongA);
        QuizAttempt stored = attempts.findById(id).orElseThrow();
        JsonNode j = result(id, token).json();
        assertEquals(stored.correctCount(), j.get("correctCount").asInt());
        assertEquals(0, stored.scorePercent().compareTo(j.get("scorePercent").decimalValue()));
        assertEquals(stored.submittedAt().toString(), j.get("submittedAt").asText());
        assertEquals(1, j.get("answeredCount").asInt());
        assertTrue(j.get("answers").get(1).get("selectedOptionId").isNull(), "unanswered question");
    }

    @Test
    void resultRequiresAuthentication() throws Exception {
        long id = submitted(token, questionAId, correctA);
        assertEquals(401, result(id, null).status());
        assertEquals(401, result(id, "A".repeat(43)).status());
    }

    @Test
    void unknownAttemptIs404() throws Exception {
        assertEquals(404, result(999_999_999L, token).status());
    }

    @Test
    void anotherStudentsAttemptIs404AndIndistinguishableFromUnknown() throws Exception {
        long mine = submitted(token, questionAId, correctA);
        String otherToken = (String) freshStudent()[0];

        Res theirs = result(mine, otherToken);
        Res unknown = result(999_999_999L, otherToken);
        assertEquals(404, theirs.status());
        assertEquals(unknown.body(), theirs.body());
    }

    @Test
    void studentIdInTheQueryStringIsIgnored() throws Exception {
        long mine = submitted(token, questionAId, correctA);
        String otherToken = (String) freshStudent()[0];
        assertEquals(404, call("GET", "/api/attempts/" + mine + "?studentId=" + studentId, null, otherToken).status());
    }

    @Test
    void resultDoesNotLeakTheAnswerKeyOrInternalFields() throws Exception {
        long id = submitted(token, questionAId, wrongA);
        JsonNode j = result(id, token).json();
        for (JsonNode a : j.get("answers")) {
            assertEquals(Set.of("questionId", "selectedOptionId", "correct"), fieldNames(a),
                    "no correct option id, option text or internal ids per answer");
        }
        String body = j.toString().toLowerCase();
        for (String forbidden : List.of("studentid", "student_id", "attempt_id", "is_correct", "correctoption",
                "password", "hash", "salt", "token")) {
            assertFalse(body.contains(forbidden), "response must not contain '" + forbidden + "': " + body);
        }
    }

    @Test
    void malformedOrNonNumericIdsAre404() throws Exception {
        for (String path : List.of("/api/attempts/abc", "/api/attempts/0", "/api/attempts/-1", "/api/attempts/",
                "/api/attempts/1.5", "/api/attempts/1/extra")) {
            assertEquals(404, call("GET", path, null, token).status(), path);
        }
    }

    @Test
    void onlyGetIsAllowedForResults() throws Exception {
        long id = submitted(token, questionAId, correctA);
        for (String method : List.of("POST", "PUT", "DELETE")) {
            assertEquals(405, call(method, "/api/attempts/" + id, method.equals("POST") ? "{}" : null, token).status(),
                    method);
        }
    }
}
