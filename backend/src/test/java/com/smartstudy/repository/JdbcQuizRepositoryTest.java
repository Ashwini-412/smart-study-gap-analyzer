package com.smartstudy.repository;

import com.smartstudy.model.Quiz;
import com.smartstudy.model.Topic;
import com.smartstudy.support.DbTestSupport;
import com.smartstudy.util.DuplicateResourceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-MySQL repository tests (skipped unless DB_PASSWORD is set). */
class JdbcQuizRepositoryTest {

    private Database db;
    private JdbcQuizRepository quizzes;
    private JdbcTopicRepository topics;
    private JdbcQuestionRepository questions;
    private Long quizId;
    private Long topicId;

    @BeforeEach
    void setUp() {
        db = DbTestSupport.databaseOrSkip();
        quizzes = new JdbcQuizRepository(db);
        topics = new JdbcTopicRepository(db);
        questions = new JdbcQuestionRepository(db);
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

    private String uniqueTitle() {
        return "Quiz-" + UUID.randomUUID();
    }

    @Test
    void createAndFindQuiz() {
        String title = uniqueTitle();
        Quiz created = quizzes.create(title, "A description");
        quizId = created.id();
        assertTrue(created.id() > 0);
        assertEquals(title, created.title());
        assertEquals("A description", created.description());
        assertTrue(created.createdAt() != null);

        Quiz found = quizzes.findById(created.id()).orElseThrow();
        assertEquals(title, found.title());
    }

    @Test
    void descriptionMayBeNull() {
        Quiz created = quizzes.create(uniqueTitle(), null);
        quizId = created.id();
        assertEquals(null, quizzes.findById(created.id()).orElseThrow().description());
    }

    @Test
    void unknownQuizIsEmpty() {
        assertTrue(quizzes.findById(Long.MAX_VALUE).isEmpty());
    }

    @Test
    void duplicateTitleIsReportedAsDuplicateResourceException() {
        String title = uniqueTitle();
        quizId = quizzes.create(title, null).id();
        assertThrows(DuplicateResourceException.class, () -> quizzes.create(title, null));
    }

    @Test
    void topicsByQuizIdsReturnsDistinctTopicsReachedThroughQuestions() {
        quizId = quizzes.create(uniqueTitle(), null).id();
        topicId = topics.create("Topic-" + UUID.randomUUID()).id();
        // Two questions on the same topic: the topic must appear once, not twice.
        questions.createWithOptions(quizId, topicId, "Q1",
                List.of(new QuestionRepository.NewOption("a", true), new QuestionRepository.NewOption("b", false)));
        questions.createWithOptions(quizId, topicId, "Q2",
                List.of(new QuestionRepository.NewOption("a", true), new QuestionRepository.NewOption("b", false)));

        Map<Long, List<Topic>> result = quizzes.topicsByQuizIds(List.of(quizId));
        assertEquals(1, result.get(quizId).size());
        assertEquals(topicId, result.get(quizId).get(0).id());
    }

    @Test
    void topicsByQuizIdsOmitsQuizzesWithNoQuestionsYet() {
        quizId = quizzes.create(uniqueTitle(), null).id();
        Map<Long, List<Topic>> result = quizzes.topicsByQuizIds(List.of(quizId));
        assertTrue(result.getOrDefault(quizId, List.of()).isEmpty());
    }

    @Test
    void sqlInjectionAttemptsAreTreatedAsPlainData() {
        String evil = "Robert'); DROP TABLE quizzes;-- " + UUID.randomUUID();
        Quiz created = quizzes.create(evil, null);
        quizId = created.id();
        assertEquals(evil, quizzes.findById(created.id()).orElseThrow().title());
        assertTrue(quizzes.findAll().size() > 0, "table still exists");
    }
}
