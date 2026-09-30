package com.smartstudy.util;

/** A student with this email already exists. Maps to HTTP 409. */
public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException() {
        super("Email is already registered");
    }
}
