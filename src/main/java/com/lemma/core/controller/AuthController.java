package com.lemma.core.controller;

import com.lemma.core.dto.LoginRequest;
import com.lemma.core.exception.ApiException;
import com.lemma.core.service.AuthService;
import io.javalin.http.Context;

/**
 * Rota de login, pública (sem token). Sem regra de negócio: lê o corpo, chama o
 * service e devolve o DTO. Corpo ausente, malformado ou com tipo errado vira
 * 400 REQUEST_INVALID no ErrorHandler; o corpo literal null é tratado aqui.
 */
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** CT3: POST /auth/login → 200 { token, expiresAt }. */
    public void login(Context ctx) {
        // O JSON literal null faria o Javalin lançar NullPointerException (500); tratamos antes.
        if ("null".equals(ctx.body().strip())) {
            throw new ApiException(400, "REQUEST_INVALID", "Corpo da requisição inválido.");
        }
        LoginRequest request = ctx.bodyAsClass(LoginRequest.class);
        ctx.status(200).json(authService.login(request.email(), request.password()));
    }
}
