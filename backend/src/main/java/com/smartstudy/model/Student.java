package com.smartstudy.model;

/** A row of students, including credential material. Never serialise this to a client. */
public record Student(long id, String name, String email, String passwordHash, String passwordSalt) {

    @Override
    public String toString() {
        return "Student[id=" + id + "]"; // no name/email/credentials in logs
    }
}
