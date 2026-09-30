package com.smartstudy.util;

import java.util.Map;

/** Input failed validation. Field messages are fixed strings and never echo the input. */
public class ValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public ValidationException(Map<String, String> fieldErrors) {
        super("Validation failed");
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
