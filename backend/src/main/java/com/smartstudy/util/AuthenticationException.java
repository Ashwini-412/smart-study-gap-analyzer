package com.smartstudy.util;

/** Credentials or session are not valid. Maps to HTTP 401. */
public class AuthenticationException extends RuntimeException {

    public AuthenticationException(String message) {
        super(message);
    }
}
