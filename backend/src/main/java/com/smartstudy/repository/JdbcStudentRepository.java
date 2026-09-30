package com.smartstudy.repository;

import com.smartstudy.model.Student;
import com.smartstudy.util.DuplicateEmailException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

public class JdbcStudentRepository implements StudentRepository {

    private static final int MYSQL_DUPLICATE_ENTRY = 1062;

    private static final String INSERT =
            "INSERT INTO students (name, email, password_hash, password_salt) VALUES (?, ?, ?, ?)";
    private static final String SELECT_BY_EMAIL =
            "SELECT id, name, email, password_hash, password_salt FROM students WHERE email = ?";
    private static final String SELECT_BY_ID =
            "SELECT id, name, email, password_hash, password_salt FROM students WHERE id = ?";

    private final Database database;

    public JdbcStudentRepository(Database database) {
        this.database = database;
    }

    @Override
    public Student create(String name, String email, String passwordHash, String passwordSalt) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, email);
            ps.setString(3, passwordHash);
            ps.setString(4, passwordSalt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new Student(keys.getLong(1), name, email, passwordHash, passwordSalt);
            }
        } catch (SQLException e) {
            if (e.getErrorCode() == MYSQL_DUPLICATE_ENTRY) {
                throw new DuplicateEmailException();
            }
            throw new DataAccessException("Failed to create student", e);
        }
    }

    @Override
    public Optional<Student> findByEmail(String email) {
        return findOne(SELECT_BY_EMAIL, ps -> ps.setString(1, email));
    }

    @Override
    public Optional<Student> findById(long id) {
        return findOne(SELECT_BY_ID, ps -> ps.setLong(1, id));
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private Optional<Student> findOne(String sql, Binder binder) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Student(rs.getLong("id"), rs.getString("name"), rs.getString("email"),
                        rs.getString("password_hash"), rs.getString("password_salt")));
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to query students", e);
        }
    }
}
