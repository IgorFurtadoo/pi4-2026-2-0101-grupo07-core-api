package com.lemma.core.service;

import com.lemma.core.dto.LoginResponse;
import com.lemma.core.exception.ApiException;
import com.lemma.core.model.User;
import com.lemma.core.repository.UserRepository;
import com.lemma.core.security.JwtService;
import com.lemma.core.security.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

/**
 * Login por e-mail e senha (CT3, seção 8.2 do desenho).
 *
 * E-mail inexistente e senha errada recebem a mesma resposta e levam o mesmo
 * tempo: quando o e-mail não existe, a senha é conferida contra um hash falso do
 * mesmo custo. Sem isso, o bcrypt só rodaria para e-mails cadastrados, e o tempo
 * de resposta revelaria quais existem.
 *
 * O login não revela regra de formato: e-mail fora do formato ou senha acima de
 * 72 bytes simplesmente não conferem (INVALID_CREDENTIALS).
 *
 * Log: só o id do usuário no sucesso; nada na falha. Nunca o e-mail, a senha, o
 * hash ou o token.
 */
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final JwtService jwtService;

    /** Hash de uma senha aleatória, gerado com o mesmo PasswordHasher (logo, o mesmo custo). */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordHasher passwordHasher, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.jwtService = jwtService;
        // Um bcrypt na subida (cerca de 250 ms com custo 12). O valor não importa:
        // sem usuário, a resposta é sempre INVALID_CREDENTIALS.
        this.dummyHash = passwordHasher.hash(UUID.randomUUID().toString());
    }

    public LoginResponse login(String rawEmail, String password) {
        String email = UserService.normalizeEmail(rawEmail);
        // Senha só com espaços não é ausente: é conferida como qualquer outra.
        if (email == null || email.isEmpty() || password == null || password.isEmpty()) {
            throw new ApiException(400, "REQUEST_INVALID", "Corpo da requisição inválido.");
        }

        Optional<User> user = findByEmail(email);

        // Sempre um bcrypt: contra o hash do usuário ou contra o hash falso.
        String hash = user.map(User::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = passwordHasher.verify(password, hash);
        if (user.isEmpty() || !passwordMatches) {
            throw invalidCredentials();
        }

        long userId = user.get().getId();
        JwtService.IssuedToken issued = jwtService.issue(userId);
        log.info("Usuário {} autenticado", userId);
        return new LoginResponse(issued.token(), issued.expiresAt().toString());
    }

    /** A mensagem do banco pode trazer o e-mail: vai ao log só a classe da exceção. */
    private Optional<User> findByEmail(String email) {
        try {
            return userRepository.findByEmail(email);
        } catch (RuntimeException e) {
            log.warn("Login: falha ao consultar o banco ({})", e.getClass().getSimpleName());
            throw new ApiException(500, "INTERNAL_ERROR", "Erro interno.");
        }
    }

    /** A mesma exceção para e-mail inexistente e senha errada (CT3). */
    private static ApiException invalidCredentials() {
        return new ApiException(401, "INVALID_CREDENTIALS", "E-mail ou senha incorretos");
    }
}
