package com.smartstudy.repository;

import com.smartstudy.model.Student;
import com.smartstudy.util.DuplicateEmailException;

import java.util.Optional;

public interface StudentRepository {

    /** Inserts a student. Email must already be normalised (trimmed, lower-case). */
    Student create(String name, String email, String passwordHash, String passwordSalt)
            throws DuplicateEmailException;

    Optional<Student> findByEmail(String email);

    Optional<Student> findById(long id);
}
