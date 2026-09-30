package com.smartstudy.service;

import com.smartstudy.dto.CreateQuizRequest;
import com.smartstudy.dto.QuizResponse;
import com.smartstudy.model.Topic;
import com.smartstudy.support.InMemoryQuizRepository;
import com.smartstudy.util.DuplicateResourceException;
import com.smartstudy.util.NotFoundException;
import com.smartstudy.util.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuizServiceTest {

    private InMemoryQuizRepository repository;
    private QuizService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryQuizRepository();
        service = new QuizService(repository);
    }

    @Test
    void createSucceedsAndTrimsFields() {
        QuizResponse r = service.create(new CreateQuizRequest("  Mathematics Basics  ", "  Core skills  "));
        assertEquals("Mathematics Basics", r.title());
        assertEquals("Core skills", r.description());
        assertTrue(r.id() > 0);
        assertEquals(List.of(), r.topics());
    }

    @Test
    void blankDescriptionIsStoredAsNull() {
        QuizResponse r = service.create(new CreateQuizRequest("Quiz A", "   "));
        assertEquals(null, r.description());
    }

    @Test
    void descriptionIsOptional() {
        QuizResponse r = service.create(new CreateQuizRequest("Quiz B", null));
        assertEquals(null, r.description());
    }

    @Test
    void missingOrBlankTitleIsRejected() {
        assertThrows(ValidationException.class, () -> service.create(new CreateQuizRequest(null, "d")));
        assertThrows(ValidationException.class, () -> service.create(new CreateQuizRequest("  ", "d")));
    }

    @Test
    void tooLongTitleOrDescriptionIsRejected() {
        var badTitle = assertThrows(ValidationException.class,
                () -> service.create(new CreateQuizRequest("x".repeat(201), null)));
        assertTrue(badTitle.fieldErrors().containsKey("title"));

        var badDescription = assertThrows(ValidationException.class,
                () -> service.create(new CreateQuizRequest("Fine title", "x".repeat(1001))));
        assertTrue(badDescription.fieldErrors().containsKey("description"));
    }

    @Test
    void duplicateTitleIsRejected() {
        service.create(new CreateQuizRequest("Same Title", null));
        assertThrows(DuplicateResourceException.class, () -> service.create(new CreateQuizRequest("Same Title", null)));
    }

    @Test
    void getByIdReturnsTheQuizWithItsTopics() {
        QuizResponse created = service.create(new CreateQuizRequest("Quiz With Topics", null));
        repository.setTopicsForQuiz(created.id(), new Topic(1, "Algebra"), new Topic(2, "Geometry"));

        QuizResponse found = service.getById(created.id());
        assertEquals(List.of("Algebra", "Geometry"),
                found.topics().stream().map(t -> t.name()).toList());
    }

    @Test
    void getByIdOnUnknownQuizThrowsNotFound() {
        assertThrows(NotFoundException.class, () -> service.getById(999_999L));
    }

    @Test
    void listIncludesTopicsPerQuizWithoutMixingThemUp() {
        QuizResponse a = service.create(new CreateQuizRequest("Quiz A", null));
        QuizResponse b = service.create(new CreateQuizRequest("Quiz B", null));
        repository.setTopicsForQuiz(a.id(), new Topic(1, "Algebra"));
        repository.setTopicsForQuiz(b.id(), new Topic(2, "Mechanics"));

        List<QuizResponse> all = service.list();
        QuizResponse foundA = all.stream().filter(q -> q.id() == a.id()).findFirst().orElseThrow();
        QuizResponse foundB = all.stream().filter(q -> q.id() == b.id()).findFirst().orElseThrow();
        assertEquals("Algebra", foundA.topics().get(0).name());
        assertEquals("Mechanics", foundB.topics().get(0).name());
    }
}
