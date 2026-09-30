package com.smartstudy.repository;

/** Unexpected database failure. The message never contains user input or credentials. */
public class DataAccessException extends RuntimeException {

    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
