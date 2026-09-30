package com.smartstudy.dto;

/** The only student fields that ever leave the server. */
public record StudentResponse(long id, String name, String email) {
}
