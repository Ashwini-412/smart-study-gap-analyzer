package com.smartstudy.service;

import com.smartstudy.dto.CreateTopicRequest;
import com.smartstudy.dto.TopicResponse;
import com.smartstudy.support.InMemoryTopicRepository;
import com.smartstudy.util.DuplicateResourceException;
import com.smartstudy.util.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicServiceTest {

    private InMemoryTopicRepository repository;
    private TopicService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTopicRepository();
        service = new TopicService(repository);
    }

    @Test
    void createSucceedsAndTrimsTheName() {
        TopicResponse r = service.create(new CreateTopicRequest("  Algebra  "));
        assertEquals("Algebra", r.name());
        assertTrue(r.id() > 0);
        assertEquals(1, repository.count());
    }

    @Test
    void listReturnsTopicsOrderedByName() {
        service.create(new CreateTopicRequest("Mechanics"));
        service.create(new CreateTopicRequest("Algebra"));
        service.create(new CreateTopicRequest("Geometry"));
        List<TopicResponse> names = service.list();
        assertEquals(List.of("Algebra", "Geometry", "Mechanics"),
                names.stream().map(TopicResponse::name).toList());
    }

    @Test
    void missingOrBlankNameIsRejected() {
        assertThrows(ValidationException.class, () -> service.create(new CreateTopicRequest(null)));
        assertThrows(ValidationException.class, () -> service.create(new CreateTopicRequest("   ")));
        assertEquals(0, repository.count());
    }

    @Test
    void tooLongNameIsRejected() {
        var e = assertThrows(ValidationException.class,
                () -> service.create(new CreateTopicRequest("x".repeat(101))));
        assertTrue(e.fieldErrors().containsKey("name"));
    }

    @Test
    void controlCharactersAreRejected() {
        var e = assertThrows(ValidationException.class,
                () -> service.create(new CreateTopicRequest("Bad\u0000Name")));
        assertTrue(e.fieldErrors().containsKey("name"));
    }

    @Test
    void duplicateNameIsRejectedCaseInsensitively() {
        service.create(new CreateTopicRequest("Algebra"));
        assertThrows(DuplicateResourceException.class, () -> service.create(new CreateTopicRequest("Algebra")));
        assertThrows(DuplicateResourceException.class, () -> service.create(new CreateTopicRequest("ALGEBRA")));
        assertEquals(1, repository.count());
    }

    @Test
    void sqlInjectionStringsAreTreatedAsPlainText() {
        String evil = "Robert'); DROP TABLE topics;--";
        TopicResponse r = service.create(new CreateTopicRequest(evil));
        assertEquals(evil, r.name());
    }
}
