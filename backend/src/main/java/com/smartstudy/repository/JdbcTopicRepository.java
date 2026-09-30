package com.smartstudy.repository;

import com.smartstudy.model.Topic;
import com.smartstudy.util.DuplicateResourceException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JdbcTopicRepository implements TopicRepository {

    private static final int MYSQL_DUPLICATE_ENTRY = 1062;

    private static final String SELECT_ALL = "SELECT id, name FROM topics ORDER BY name";
    private static final String SELECT_BY_ID = "SELECT id, name FROM topics WHERE id = ?";
    private static final String INSERT = "INSERT INTO topics (name) VALUES (?)";

    private final Database database;

    public JdbcTopicRepository(Database database) {
        this.database = database;
    }

    @Override
    public List<Topic> findAll() {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_ALL);
             ResultSet rs = ps.executeQuery()) {
            List<Topic> topics = new ArrayList<>();
            while (rs.next()) {
                topics.add(map(rs));
            }
            return topics;
        } catch (SQLException e) {
            throw new DataAccessException("Failed to query topics", e);
        }
    }

    @Override
    public Optional<Topic> findById(long id) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_BY_ID)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to query topics", e);
        }
    }

    @Override
    public Topic create(String name) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new Topic(keys.getLong(1), name);
            }
        } catch (SQLException e) {
            if (e.getErrorCode() == MYSQL_DUPLICATE_ENTRY) {
                throw new DuplicateResourceException("A topic with this name already exists");
            }
            throw new DataAccessException("Failed to create topic", e);
        }
    }

    private static Topic map(ResultSet rs) throws SQLException {
        return new Topic(rs.getLong("id"), rs.getString("name"));
    }
}
