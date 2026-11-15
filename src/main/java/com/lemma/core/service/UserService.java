package com.lemma.core.service;

import com.lemma.core.client.BlockchainClient;
import com.lemma.core.client.BlockchainUnavailableException;
import com.lemma.core.dto.RegisterUserResponse;
import com.lemma.core.exception.ApiException;
import com.lemma.core.model.User;
import com.lemma.core.repository.EmailAlreadyRegisteredException;
import com.lemma.core.repository.UserRepository;
import com.lemma.core.security.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Cadastro de usuário (CT1, seção 8.1 do desenho).
 *
 * Ordem: validar → conferir se o e-mail existe → gerar o hash → CT4 (cria a
 * carteira) → gravar → responder. O hash vem antes do CT4 para não deixar uma
 * carteira criada à espera do bcrypt. Se a carteira falhar, nada é gravado.
 *
 * Nunca loga a senha nem o e-mail; as mensagens do banco também não, porque
 * trazem o e-mail.
 */
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** Formato simples (seção 6): algo@algo.algo, sem espaço. */
    static final Pattern EMAIL_FORMAT = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    static final int EMAIL_MAX_CHARS = 254;
    static final int PASSWORD_MIN_CHARS = 8;

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final BlockchainClient blockchainClient;

    public UserService(UserRepository userRepository, PasswordHasher passwordHasher,
                       BlockchainClient blockchainClient) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.blockchainClient = blockchainClient;
    }

    public RegisterUserResponse register(String rawEmail, String password) {
        String email = normalizeEmail(rawEmail);
        validateEmail(email);
        validatePassword(password);

        if (findByEmail(email).isPresent()) {
            throw emailAlreadyRegistered();
        }

        String passwordHash = passwordHasher.hash(password);

        String ethereumAddress;
        try {
            ethereumAddress = blockchainClient.createWallet();
        } catch (BlockchainUnavailableException e) {
            // O motivo já foi ao log pelo cliente.
            throw new ApiException(502, "WALLET_UNAVAILABLE",
                    "Não foi possível criar a carteira agora. Tente novamente.");
        }

        // DATETIME(3): o MySQL arredondaria a fração além do milissegundo.
        User user = new User(email, passwordHash, ethereumAddress, Instant.now().truncatedTo(ChronoUnit.MILLIS));
        save(user);

        log.info("Usuário {} cadastrado", user.getId());
        return new RegisterUserResponse(user.getId(), user.getEmail(), user.getEthereumAddress());
    }

    /** trim e minúsculas (Locale.ROOT), antes de validar, buscar e gravar. Nulo continua nulo. */
    static String normalizeEmail(String rawEmail) {
        return rawEmail == null ? null : rawEmail.trim().toLowerCase(Locale.ROOT);
    }

    private static void validateEmail(String email) {
        if (email == null
                || email.codePointCount(0, email.length()) > EMAIL_MAX_CHARS
                || !EMAIL_FORMAT.matcher(email).matches()) {
            throw new ApiException(400, "EMAIL_INVALID", "Informe um e-mail válido.");
        }
    }

    /** No mínimo 8 caracteres e no máximo 72 bytes em UTF-8 (limite do bcrypt). */
    private static void validatePassword(String password) {
        if (password == null
                || password.codePointCount(0, password.length()) < PASSWORD_MIN_CHARS
                || password.getBytes(StandardCharsets.UTF_8).length > PasswordHasher.MAX_PASSWORD_BYTES) {
            throw new ApiException(400, "PASSWORD_INVALID",
                    "A senha precisa ter no mínimo 8 caracteres e no máximo 72 bytes.");
        }
    }

    private Optional<User> findByEmail(String email) {
        try {
            return userRepository.findByEmail(email);
        } catch (RuntimeException e) {
            log.warn("Cadastro: falha ao consultar o banco ({})", e.getClass().getSimpleName());
            throw internalError();
        }
    }

    /**
     * Grava o usuário. Se falhar, a carteira criada no CT4 fica órfã (aceito na
     * seção 8.1): o endereço vai ao log em WARN para ser rastreável.
     */
    private void save(User user) {
        String address = user.getEthereumAddress();
        try {
            userRepository.save(user);
        } catch (EmailAlreadyRegisteredException e) {
            // Corrida: outro cadastro com o mesmo e-mail gravou primeiro.
            log.warn("Carteira órfã {}: o e-mail foi cadastrado por outra requisição", address);
            throw emailAlreadyRegistered();
        } catch (RuntimeException e) {
            log.warn("Carteira órfã {}: falha ao gravar o usuário ({})", address, e.getClass().getSimpleName());
            throw internalError();
        }
    }

    private static ApiException emailAlreadyRegistered() {
        return new ApiException(409, "EMAIL_ALREADY_REGISTERED", "E-mail já cadastrado.");
    }

    private static ApiException internalError() {
        return new ApiException(500, "INTERNAL_ERROR", "Erro interno.");
    }
}
