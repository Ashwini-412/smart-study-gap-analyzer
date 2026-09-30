package com.smartstudy.dto;

/** Returned once at login. {@code token} is the raw session token; expiresAt is ISO-8601 UTC. */
public record LoginResponse(String token, String tokenType, String expiresAt, StudentResponse student) {

    @Override
    public String toString() {
        return "LoginResponse[redacted]";
    }
}
