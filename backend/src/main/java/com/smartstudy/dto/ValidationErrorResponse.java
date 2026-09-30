package com.smartstudy.dto;

import java.util.Map;

public record ValidationErrorResponse(String error, Map<String, String> fields) {
}
