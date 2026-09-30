package com.smartstudy.repository;

import com.smartstudy.model.Topic;
import com.smartstudy.repository.PerformanceRepository.TopicStats;
import com.smartstudy.support.DbTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The topic-performance aggregation against real MySQL (skipped unless DB_PASSWORD is set). */
class JdbcPerformanceRepositoryTest {

    private Database db;
    private JdbcPerformanceRepository performance;
    private JdbcQuizAttemptRepository attempts;
    private JdbcQuestionRepository questions;

    private final List<String> emails = new ArrayList<>();
    private final List<Long> attemptIds = new ArrayList<>();
    private final List<Long> quizIds = new ArrayList<>();
    private final List<Long> topicIds = new ArrayList<>();

    private long studentA;
    private long studentB;
    private long answeredTopic;
    private long emptyTopic;
    private long quiz1;
    private long quiz2;
    private QuestionRepository.Created qa;
    private QuestionRepository.Created qb;

    @BeforeEach
    void setUp() {
        db = DbTestSupport.databaseOrSkip();
        performance = new JdbcPerformanceRepository(db);
        attempts = new JdbcQuizAttemptRepository(db);
        questions = new JdbcQuestionRepository(db);
        JdbcStudentRepository students = new JdbcStudentRepository(db);
        JdbcQuizRepository quizzes = new JdbcQuizRepository(db);
        JdbcTopicRepository topics = new JdbcTopicRepository(db);

        studentA = students.create("Gap A", email(), "h", "s").id();
        studentB = students.create("Gap B", email(), "h", "s").id();
        String suffix = UUID.randomUUID().toString();
        answeredTopic = topics.create("M12-A-" + suffix).id();
        emptyTopic = topics.create("M12-B-" + suffix).id();
        topicIds.add(answeredTopic);
        topicIds.add(emptyTopic);
        quiz1 = quizzes.create("Quiz-" + UUID.randomUUID(), null).id();
        quiz2 = quizzes.create("Quiz-" + UUID.randomUUID(), null).id();
        quizIds.add(quiz1);
        quizIds.add(quiz2);

        qa = questions.createWithOptions(quiz1, answeredTopic, "A",
                List.of(new QuestionRepository.NewOption("right", true), new QuestionRepository.NewOption("wrong", false)));
        qb = questions.createWithOptions(quiz2, answeredTopic, "B",
                List.of(new QuestionRepository.NewOption("right", true), new QuestionRepository.NewOption("wrong", false)));
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (db == null) {
            return;
        }
        for (long id : attemptIds) {
            DbTestSupport.deleteAttemptById(db, id); // before quizzes: quizzes.id is RESTRICT
        }
        for (long id : quizIds) {
            DbTestSupport.deleteQuizById(db, id); // cascades to questions/options
        }
        for (long id : topicIds) {
            DbTestSupport.deleteTopicById(db, id);
        }
        for (String e : emails) {
            DbTestSupport.deleteStudentByEmail(db, e);
        }
    }

    private String email() {
        String e = "gap-repo-" + UUID.randomUUID() + "@example.test";
        emails.add(e);
        return e;
    }

    private long optionId(QuestionRepository.Created q, boolean correct) {
        return q.options().stream().filter(o -> o.correct() == correct).findFirst().orElseThrow().id();
    }

    private void attempt(long student, long quiz, QuizAttemptRepository.NewAnswer... answers) {
        int correct = (int) java.util.Arrays.stream(answers).filter(QuizAttemptRepository.NewAnswer::correct).count();
        BigDecimal score = BigDecimal.valueOf(correct * 100L).divide(BigDecimal.valueOf(answers.length), 2,
                java.math.RoundingMode.HALF_UP);
        attemptIds.add(attempts.createWithAnswers(student, quiz, answers.length, correct, score, List.of(answers))
                .attempt().id());
    }

    private static TopicStats statsFor(List<TopicStats> all, long topicId) {
        return all.stream().filter(s -> s.topicId() == topicId).findFirst()
                .orElseThrow(() -> new AssertionError("topic " + topicId + " missing"));
    }

    @Test
    void aggregatesCumulativelyAcrossAttemptsAndQuizzesIncludingUnanswered() {
        attempt(studentA, quiz1, new QuizAttemptRepository.NewAnswer(qa.question().id(), optionId(qa, true), true));
        attempt(studentA, quiz1, new QuizAttemptRepository.NewAnswer(qa.question().id(), optionId(qa, false), false));
        attempt(studentA, quiz2, new QuizAttemptRepository.NewAnswer(qb.question().id(), null, false)); // unanswered

        TopicStats s = statsFor(performance.topicStatsForStudent(studentA), answeredTopic);
        assertEquals(3, s.totalQuestions(), "two attempts of quiz1 + one of quiz2, unanswered included");
        assertEquals(1, s.correctCount());
    }

    @Test
    void onlyTheGivenStudentsAttemptsContribute() {
        attempt(studentA, quiz1, new QuizAttemptRepository.NewAnswer(qa.question().id(), optionId(qa, false), false));
        attempt(studentB, quiz1, new QuizAttemptRepository.NewAnswer(qa.question().id(), optionId(qa, true), true));

        TopicStats a = statsFor(performance.topicStatsForStudent(studentA), answeredTopic);
        TopicStats b = statsFor(performance.topicStatsForStudent(studentB), answeredTopic);
        assertEquals(1, a.totalQuestions());
        assertEquals(0, a.correctCount());
        assertEquals(1, b.totalQuestions());
        assertEquals(1, b.correctCount());
    }

    @Test
    void topicsWithNoAnswersAreListedAsZeroOverZero() {
        attempt(studentA, quiz1, new QuizAttemptRepository.NewAnswer(qa.question().id(), optionId(qa, true), true));

        TopicStats empty = statsFor(performance.topicStatsForStudent(studentA), emptyTopic);
        assertEquals(0, empty.totalQuestions());
        assertEquals(0, empty.correctCount());
        assertTrue(performance.topicStatsForStudent(Long.MAX_VALUE).stream()
                .allMatch(s -> s.totalQuestions() == 0 && s.correctCount() == 0), "unknown student: nothing counted");
    }

    @Test
    void storedCorrectnessIsUsedNotRederived() {
        // Stored as correct even though the option is the wrong one: the query must trust the snapshot.
        attempt(studentA, quiz1, new QuizAttemptRepository.NewAnswer(qa.question().id(), optionId(qa, false), true));
        assertEquals(1, statsFor(performance.topicStatsForStudent(studentA), answeredTopic).correctCount());
    }

    @Test
    void laterQuizEditsDoNotChangeHistory() {
        attempt(studentA, quiz1, new QuizAttemptRepository.NewAnswer(qa.question().id(), optionId(qa, true), true));
        TopicStats before = statsFor(performance.topicStatsForStudent(studentA), answeredTopic);

        questions.createWithOptions(quiz1, answeredTopic, "Added later",
                List.of(new QuestionRepository.NewOption("x", true), new QuestionRepository.NewOption("y", false)));

        assertEquals(before, statsFor(performance.topicStatsForStudent(studentA), answeredTopic));
    }

    @Test
    void everyTopicIsListedInNameOrder() {
        List<Long> fromPerformance = performance.topicStatsForStudent(studentA).stream().map(TopicStats::topicId).toList();
        List<Long> fromTopics = new JdbcTopicRepository(db).findAll().stream().map(Topic::id).toList();
        assertEquals(fromTopics, fromPerformance, "same topics, same (name) order as GET /api/topics");
        assertTrue(fromPerformance.indexOf(answeredTopic) < fromPerformance.indexOf(emptyTopic));
    }
}
