package com.smartstudy.dto;

public record LoginRequest(String email, String password) {

    @Override
    public String toString() {
        return "LoginRequest[redacted]";
    }
}
