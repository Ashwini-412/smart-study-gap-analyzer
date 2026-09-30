package com.smartstudy.util;

/** The request itself is malformed (bad JSON, oversized body). Maps to HTTP 400. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
