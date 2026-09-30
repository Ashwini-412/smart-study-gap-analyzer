package com.smartstudy.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class JdbcPerformanceRepository implements PerformanceRepository {

    // The inner query aggregates this student's stored answers per topic; the outer LEFT JOIN keeps
    // topics with no answers (0 / 0). Correctness is the stored is_correct snapshot, never re-derived
    // from current question_options. Every stored row counts, including unanswered ones
    // (selected_option_id NULL, is_correct FALSE), so unanswered questions are in the denominator.
    private static final String TOPIC_STATS_FOR_STUDENT =
            "SELECT t.id AS topic_id, t.name AS topic_name, "
                    + "COALESCE(s.total_questions, 0) AS total_questions, COALESCE(s.correct_count, 0) AS correct_count "
                    + "FROM topics t LEFT JOIN ("
                    + "  SELECT q.topic_id, COUNT(*) AS total_questions, SUM(aa.is_correct) AS correct_count "
                    + "  FROM attempt_answers aa "
                    + "  JOIN quiz_attempts qa ON qa.id = aa.attempt_id "
                    + "  JOIN questions q ON q.id = aa.question_id "
                    + "  WHERE qa.student_id = ? "
                    + "  GROUP BY q.topic_id"
                    + ") s ON s.topic_id = t.id "
                    + "ORDER BY t.name, t.id";

    private final Database database;

    public JdbcPerformanceRepository(Database database) {
        this.database = database;
    }

    @Override
    public List<TopicStats> topicStatsForStudent(long studentId) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(TOPIC_STATS_FOR_STUDENT)) {
            ps.setLong(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                List<TopicStats> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new TopicStats(rs.getLong("topic_id"), rs.getString("topic_name"),
                            rs.getInt("total_questions"), rs.getInt("correct_count")));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to query topic performance", e);
        }
    }
}
