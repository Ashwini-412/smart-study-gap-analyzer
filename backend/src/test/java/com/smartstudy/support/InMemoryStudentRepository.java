package com.smartstudy.support;

import com.smartstudy.model.Student;
import com.smartstudy.repository.StudentRepository;
import com.smartstudy.util.DuplicateEmailException;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryStudentRepository implements StudentRepository {

    private final Map<Long, Student> byId = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    @Override
    public synchronized Student create(String name, String email, String passwordHash, String passwordSalt) {
        if (findByEmail(email).isPresent()) {
            throw new DuplicateEmailException();
        }
        Student s = new Student(ids.incrementAndGet(), name, email, passwordHash, passwordSalt);
        byId.put(s.id(), s);
        return s;
    }

    @Override
    public Optional<Student> findByEmail(String email) {
        return byId.values().stream().filter(s -> s.email().equalsIgnoreCase(email)).findFirst();
    }

    @Override
    public Optional<Student> findById(long id) {
        return Optional.ofNullable(byId.get(id));
    }

    public int count() {
        return byId.size();
    }
}
