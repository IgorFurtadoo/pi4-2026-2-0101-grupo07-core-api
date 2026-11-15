package com.lemma.core.dto;

/** Corpo de erro de todos os endpoints: { "code": "...", "message": "..." }. */
public record ErrorResponse(String code, String message) {
}
