package com.smartstudy.util;

/** A resource with this unique value already exists (e.g. topic name, quiz title). Maps to HTTP 409. */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
