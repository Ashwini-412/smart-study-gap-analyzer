package com.smartstudy.repository;

import com.smartstudy.model.Topic;
import com.smartstudy.support.DbTestSupport;
import com.smartstudy.util.DuplicateResourceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-MySQL repository tests (skipped unless DB_PASSWORD is set). */
class JdbcTopicRepositoryTest {

    private Database db;
    private JdbcTopicRepository topics;
    private Long createdId;

    @BeforeEach
    void setUp() {
        db = DbTestSupport.databaseOrSkip();
        topics = new JdbcTopicRepository(db);
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (db != null && createdId != null) {
            DbTestSupport.deleteTopicById(db, createdId);
        }
    }

    private String uniqueName() {
        return "Topic-" + UUID.randomUUID();
    }

    @Test
    void createAndFindTopic() {
        String name = uniqueName();
        Topic created = topics.create(name);
        createdId = created.id();
        assertTrue(created.id() > 0);

        Topic found = topics.findById(created.id()).orElseThrow();
        assertEquals(name, found.name());
    }

    @Test
    void unknownTopicIsEmpty() {
        assertTrue(topics.findById(Long.MAX_VALUE).isEmpty());
    }

    @Test
    void findAllIncludesCreatedTopicOrderedByName() {
        String name = "AAAA-" + UUID.randomUUID();
        createdId = topics.create(name).id();
        List<Topic> all = topics.findAll();
        assertTrue(all.stream().anyMatch(t -> t.id() == createdId));
        List<String> names = all.stream().map(Topic::name).toList();
        List<String> sorted = names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        assertEquals(sorted, names, "findAll must be ordered by name");
    }

    @Test
    void duplicateNameIsReportedAsDuplicateResourceException() {
        String name = uniqueName();
        createdId = topics.create(name).id();
        assertThrows(DuplicateResourceException.class, () -> topics.create(name));
    }

    @Test
    void sqlInjectionAttemptsAreTreatedAsPlainData() {
        String evil = "Robert'); DROP TABLE topics;-- " + UUID.randomUUID();
        Topic created = topics.create(evil);
        createdId = created.id();
        assertEquals(evil, topics.findById(created.id()).orElseThrow().name());
        assertTrue(topics.findAll().size() > 0, "table still exists");
    }
}
