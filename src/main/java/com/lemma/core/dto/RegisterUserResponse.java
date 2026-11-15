package com.lemma.core.dto;

/** Saída do CT1 registerUser: 201 { userId, email, ethereumAddress }. */
public record RegisterUserResponse(long userId, String email, String ethereumAddress) {
}
