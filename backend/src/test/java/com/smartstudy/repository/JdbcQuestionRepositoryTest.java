package com.smartstudy.repository;

import com.smartstudy.model.Question;
import com.smartstudy.model.QuestionOption;
import com.smartstudy.support.DbTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-MySQL repository tests (skipped unless DB_PASSWORD is set). */
class JdbcQuestionRepositoryTest {

    private Database db;
    private JdbcQuestionRepository questions;
    private JdbcQuizRepository quizzes;
    private JdbcTopicRepository topics;
    private Long quizId;
    private Long topicId;

    @BeforeEach
    void setUp() {
        db = DbTestSupport.databaseOrSkip();
        questions = new JdbcQuestionRepository(db);
        quizzes = new JdbcQuizRepository(db);
        topics = new JdbcTopicRepository(db);
        quizId = quizzes.create("Quiz-" + UUID.randomUUID(), null).id();
        topicId = topics.create("Topic-" + UUID.randomUUID()).id();
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (db != null && quizId != null) {
            DbTestSupport.deleteQuizById(db, quizId); // cascades to its questions/options
        }
        if (db != null && topicId != null) {
            DbTestSupport.deleteTopicById(db, topicId);
        }
    }

    private static List<QuestionRepository.NewOption> options(Object... textCorrectPairs) {
        List<QuestionRepository.NewOption> out = new java.util.ArrayList<>();
        for (int i = 0; i < textCorrectPairs.length; i += 2) {
            out.add(new QuestionRepository.NewOption((String) textCorrectPairs[i], (Boolean) textCorrectPairs[i + 1]));
        }
        return out;
    }

    @Test
    void createWithOptionsPersistsQuestionAndAllOptionsTogether() {
        QuestionRepository.Created created = questions.createWithOptions(quizId, topicId, "2 + 2 = ?",
                options("3", false, "4", true, "5", false));

        assertTrue(created.question().id() > 0);
        assertEquals(1, created.question().position());
        assertEquals(3, created.options().size());
        assertTrue(created.options().stream().allMatch(o -> o.id() > 0));

        List<Question> found = questions.findByQuizId(quizId);
        assertEquals(1, found.size());
        assertEquals("2 + 2 = ?", found.get(0).questionText());

        Map<Long, List<QuestionOption>> byId = questions.optionsByQuestionIds(List.of(found.get(0).id()));
        assertEquals(3, byId.get(found.get(0).id()).size());
    }

    @Test
    void positionsAreAssignedServerSideInCreationOrder() {
        QuestionRepository.Created first = questions.createWithOptions(quizId, topicId, "Q1", options("a", true, "b", false));
        QuestionRepository.Created second = questions.createWithOptions(quizId, topicId, "Q2", options("a", true, "b", false));
        assertEquals(1, first.question().position());
        assertEquals(2, second.question().position());
    }

    @Test
    void optionsPreserveInsertionOrder() {
        QuestionRepository.Created created = questions.createWithOptions(quizId, topicId, "Q1",
                options("first", false, "second", true, "third", false));
        List<String> texts = created.options().stream().map(QuestionOption::optionText).toList();
        assertEquals(List.of("first", "second", "third"), texts);
    }

    @Test
    void findByQuizIdForUnknownOrEmptyQuizIsEmpty() {
        assertTrue(questions.findByQuizId(Long.MAX_VALUE).isEmpty());
    }

    @Test
    void sqlInjectionAttemptsAreTreatedAsPlainData() {
        String evil = "Robert'); DROP TABLE questions;-- ";
        QuestionRepository.Created created = questions.createWithOptions(quizId, topicId, evil,
                options("a", true, "b", false));
        assertEquals(evil, created.question().questionText());
        assertTrue(questions.findByQuizId(quizId).size() > 0, "table still exists");
    }

    /**
     * A NOT NULL violation on the second option (simulating a mid-transaction failure) must roll
     * back the whole operation: neither the question nor the first, already-inserted option may
     * survive. This is the real transactional guarantee; service-level rollback behaviour is
     * covered separately (with a fake repository) in QuestionServiceTest.
     */
    @Test
    void failureInsertingAnOptionRollsBackTheQuestionToo() throws Exception {
        List<QuestionRepository.NewOption> optionsWithAnInvalidOne =
                List.of(new QuestionRepository.NewOption("valid text", false),
                        new QuestionRepository.NewOption(null, true)); // option_text is NOT NULL

        assertThrows(RuntimeException.class,
                () -> questions.createWithOptions(quizId, topicId, "Doomed question", optionsWithAnInvalidOne));

        assertTrue(questions.findByQuizId(quizId).isEmpty(), "the question must not have been committed");
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM questions WHERE quiz_id = ? AND question_text = 'Doomed question'")) {
            ps.setLong(1, quizId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1), "no orphan question row");
            }
        }
    }
}
