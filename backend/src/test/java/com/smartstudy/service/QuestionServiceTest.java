package com.smartstudy.service;

import com.smartstudy.dto.CreateQuestionRequest;
import com.smartstudy.dto.CreateQuestionRequest.OptionInput;
import com.smartstudy.dto.CreateQuizRequest;
import com.smartstudy.dto.QuestionAdminResponse;
import com.smartstudy.dto.QuestionResponse;
import com.smartstudy.dto.TopicResponse;
import com.smartstudy.support.InMemoryQuestionRepository;
import com.smartstudy.support.InMemoryQuizRepository;
import com.smartstudy.support.InMemoryTopicRepository;
import com.smartstudy.util.NotFoundException;
import com.smartstudy.util.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionServiceTest {

    private InMemoryQuestionRepository questions;
    private InMemoryQuizRepository quizzes;
    private InMemoryTopicRepository topics;
    private QuestionService service;
    private long quizId;
    private long topicId;

    @BeforeEach
    void setUp() {
        questions = new InMemoryQuestionRepository();
        quizzes = new InMemoryQuizRepository();
        topics = new InMemoryTopicRepository();
        service = new QuestionService(questions, quizzes, topics);
        quizId = quizzes.create("Quiz A", null).id();
        topicId = topics.create("Algebra").id();
    }

    private CreateQuestionRequest validRequest() {
        return new CreateQuestionRequest(topicId, "What is 2 + 2?",
                List.of(new OptionInput("3", false), new OptionInput("4", true), new OptionInput("5", false)));
    }

    // ---- listing ----

    @Test
    void listForUnknownQuizThrowsNotFound() {
        assertThrows(NotFoundException.class, () -> service.listForQuiz(999_999L));
    }

    @Test
    void listNeverIncludesWhichOptionIsCorrect() {
        service.create(quizId, validRequest());
        List<QuestionResponse> found = service.listForQuiz(quizId);
        assertEquals(1, found.size());
        assertEquals(3, found.get(0).options().size());
        // QuestionResponse.Option has no "correct" field at all: this checks it never leaks via toString either.
        assertFalse(found.get(0).options().toString().toLowerCase().contains("true"));
    }

    @Test
    void listOrdersQuestionsByPositionAndOptionsById() {
        service.create(quizId, validRequest());
        service.create(quizId, new CreateQuestionRequest(topicId, "What is 3 + 3?",
                List.of(new OptionInput("6", true), new OptionInput("5", false))));

        List<QuestionResponse> found = service.listForQuiz(quizId);
        assertEquals(1, found.get(0).position());
        assertEquals(2, found.get(1).position());
        assertEquals(List.of("3", "4", "5"), found.get(0).options().stream().map(o -> o.text()).toList());
    }

    // ---- creation: existence checks ----

    @Test
    void createForUnknownQuizThrowsNotFound() {
        assertThrows(NotFoundException.class, () -> service.create(999_999L, validRequest()));
        assertEquals(0, questions.questionCount());
    }

    @Test
    void createWithUnknownTopicIsRejected() {
        var e = assertThrows(ValidationException.class,
                () -> service.create(quizId, new CreateQuestionRequest(999_999L, "Text",
                        List.of(new OptionInput("a", true), new OptionInput("b", false)))));
        assertTrue(e.fieldErrors().containsKey("topicId"));
        assertEquals(0, questions.questionCount());
    }

    @Test
    void createWithMissingTopicIsRejected() {
        var e = assertThrows(ValidationException.class,
                () -> service.create(quizId, new CreateQuestionRequest(null, "Text",
                        List.of(new OptionInput("a", true), new OptionInput("b", false)))));
        assertTrue(e.fieldErrors().containsKey("topicId"));
    }

    // ---- creation: question text ----

    @Test
    void blankOrMissingQuestionTextIsRejected() {
        assertThrows(ValidationException.class, () -> service.create(quizId,
                new CreateQuestionRequest(topicId, null, List.of(new OptionInput("a", true), new OptionInput("b", false)))));
        assertThrows(ValidationException.class, () -> service.create(quizId,
                new CreateQuestionRequest(topicId, "   ", List.of(new OptionInput("a", true), new OptionInput("b", false)))));
    }

    @Test
    void tooLongQuestionTextIsRejected() {
        var e = assertThrows(ValidationException.class, () -> service.create(quizId,
                new CreateQuestionRequest(topicId, "x".repeat(1001),
                        List.of(new OptionInput("a", true), new OptionInput("b", false)))));
        assertTrue(e.fieldErrors().containsKey("questionText"));
    }

    // ---- creation: option structure ----

    @Test
    void tooFewOptionsIsRejected() {
        var e = assertThrows(ValidationException.class, () -> service.create(quizId,
                new CreateQuestionRequest(topicId, "Text", List.of(new OptionInput("only one", true)))));
        assertTrue(e.fieldErrors().containsKey("options"));
    }

    @Test
    void tooManyOptionsIsRejected() {
        List<OptionInput> options = List.of(
                new OptionInput("a", true), new OptionInput("b", false), new OptionInput("c", false),
                new OptionInput("d", false), new OptionInput("e", false), new OptionInput("f", false),
                new OptionInput("g", false));
        var e = assertThrows(ValidationException.class,
                () -> service.create(quizId, new CreateQuestionRequest(topicId, "Text", options)));
        assertTrue(e.fieldErrors().containsKey("options"));
    }

    @Test
    void missingOptionsIsRejected() {
        var e = assertThrows(ValidationException.class,
                () -> service.create(quizId, new CreateQuestionRequest(topicId, "Text", null)));
        assertTrue(e.fieldErrors().containsKey("options"));
    }

    @Test
    void blankOptionTextIsRejected() {
        var e = assertThrows(ValidationException.class, () -> service.create(quizId,
                new CreateQuestionRequest(topicId, "Text", List.of(new OptionInput("  ", true), new OptionInput("b", false)))));
        assertTrue(e.fieldErrors().keySet().stream().anyMatch(k -> k.contains("text")));
        assertEquals(0, questions.questionCount());
    }

    @Test
    void nullOptionElementIsRejectedAsValidationNotACrash() {
        List<OptionInput> withNull = java.util.Arrays.asList(null, new OptionInput("b", true));
        var e = assertThrows(ValidationException.class,
                () -> service.create(quizId, new CreateQuestionRequest(topicId, "Text", withNull)));
        assertTrue(e.fieldErrors().containsKey("options[0]"));
        assertEquals(0, questions.questionCount());
    }

    @Test
    void noCorrectOptionIsRejected() {
        var e = assertThrows(ValidationException.class, () -> service.create(quizId,
                new CreateQuestionRequest(topicId, "Text", List.of(new OptionInput("a", false), new OptionInput("b", false)))));
        assertTrue(e.fieldErrors().containsKey("options"));
    }

    @Test
    void twoCorrectOptionsIsRejected() {
        var e = assertThrows(ValidationException.class, () -> service.create(quizId,
                new CreateQuestionRequest(topicId, "Text", List.of(new OptionInput("a", true), new OptionInput("b", true)))));
        assertTrue(e.fieldErrors().containsKey("options"));
    }

    @Test
    void nullCorrectFlagIsTreatedAsFalse() {
        var e = assertThrows(ValidationException.class, () -> service.create(quizId,
                new CreateQuestionRequest(topicId, "Text", List.of(new OptionInput("a", null), new OptionInput("b", null)))));
        assertTrue(e.fieldErrors().containsKey("options"));
    }

    // ---- creation: success ----

    @Test
    void successfulCreationReturnsTheAdminRepresentationWithCorrectFlags() {
        QuestionAdminResponse r = service.create(quizId, validRequest());
        assertEquals(quizId, r.quizId());
        assertEquals(topicId, r.topicId());
        assertEquals("What is 2 + 2?", r.questionText());
        assertEquals(1, r.position());
        assertEquals(3, r.options().size());
        assertEquals(1, r.options().stream().filter(QuestionAdminResponse.Option::correct).count());
        assertEquals("4", r.options().stream().filter(QuestionAdminResponse.Option::correct)
                .findFirst().orElseThrow().text());
        assertEquals(1, questions.questionCount());
        assertEquals(3, questions.optionCount());
    }

    @Test
    void positionsAreAssignedServerSideAndIncrementPerQuiz() {
        QuestionAdminResponse first = service.create(quizId, validRequest());
        QuestionAdminResponse second = service.create(quizId, validRequest());
        assertEquals(1, first.position());
        assertEquals(2, second.position());
    }

    @Test
    void sqlInjectionStringsInTextAreTreatedAsPlainData() {
        String evil = "Robert'); DROP TABLE questions;--";
        QuestionAdminResponse r = service.create(quizId,
                new CreateQuestionRequest(topicId, evil, List.of(new OptionInput("a", true), new OptionInput("b", false))));
        assertEquals(evil, r.questionText());
    }

    // ---- transaction rollback ----

    @Test
    void failureDuringOptionInsertionLeavesNoPartialQuestion() {
        questions.failNextOptionInsert(new RuntimeException("simulated failure inserting an option"));
        assertThrows(RuntimeException.class, () -> service.create(quizId, validRequest()));
        assertEquals(0, questions.questionCount(), "no partial/orphan question after a failed creation");
        assertEquals(0, questions.optionCount());
        assertTrue(service.listForQuiz(quizId).isEmpty());
    }
}
