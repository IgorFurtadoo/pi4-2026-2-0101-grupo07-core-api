package com.lemma.core.controller;

import com.lemma.core.dto.RegisterUserRequest;
import com.lemma.core.exception.ApiException;
import com.lemma.core.service.UserService;
import io.javalin.http.Context;

/**
 * Rota de cadastro, pública (sem token). Sem regra de negócio: lê o corpo, chama
 * o service e devolve o DTO. Corpo ausente, malformado ou com tipo errado vira
 * 400 REQUEST_INVALID no ErrorHandler; o corpo literal null é tratado aqui.
 */
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** CT1: POST /users → 201 { userId, email, ethereumAddress }. */
    public void register(Context ctx) {
        // O JSON literal null faria o Javalin lançar NullPointerException (500); tratamos antes.
        if ("null".equals(ctx.body().strip())) {
            throw new ApiException(400, "REQUEST_INVALID", "Corpo da requisição inválido.");
        }
        RegisterUserRequest request = ctx.bodyAsClass(RegisterUserRequest.class);
        ctx.status(201).json(userService.register(request.email(), request.password()));
    }
}
