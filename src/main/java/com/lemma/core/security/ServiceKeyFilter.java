package com.lemma.core.security;

import com.lemma.core.exception.ApiException;
import io.javalin.http.Context;
import io.javalin.router.Endpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * Exige o cabeçalho X-Service-Key nas rotas entre serviços (premissa P2, seção 6
 * do desenho): hoje, só o webhook POST /webhooks/tasks (CT15). Essas rotas não
 * usam token; o TokenFilter as dispensa porque estão em Main.PUBLIC_ROUTES.
 *
 * Registrado no Main com beforeMatched, como o TokenFilter: só roda quando existe
 * rota para o caminho e o método, então 404 e 405 ficam como estão.
 *
 * Cabeçalho ausente, vazio ou diferente da SERVICE_KEY: 401 SERVICE_KEY_INVALID,
 * com WARN no log. A chave (a esperada e a recebida) nunca vai ao log.
 */
public class ServiceKeyFilter {

    private static final Logger log = LoggerFactory.getLogger(ServiceKeyFilter.class);

    static final String HEADER = "X-Service-Key";

    private final byte[] expectedKey;
    private final Set<String> routes;

    /**
     * @param serviceKey a SERVICE_KEY da configuração
     * @param routes     rotas que exigem a chave, no formato "MÉTODO /caminho-registrado"
     */
    public ServiceKeyFilter(String serviceKey, Set<String> routes) {
        this.expectedKey = serviceKey.getBytes(StandardCharsets.UTF_8);
        this.routes = Set.copyOf(routes);
    }

    public void handle(Context ctx) {
        String route = matchedRoute(ctx);
        if (route == null || !routes.contains(route)) {
            return;
        }
        if (!isValid(ctx.header(HEADER))) {
            log.warn("Chave de serviço ausente ou incorreta em {}", route);
            throw new ApiException(401, "SERVICE_KEY_INVALID", "Chave de serviço ausente ou incorreta.");
        }
    }

    /**
     * Comparação em tempo constante: MessageDigest.isEqual percorre sempre a chave
     * esperada inteira (1º argumento), sem parar no primeiro byte diferente, então
     * o tempo não revela quantos caracteres da chave enviada estão certos.
     * Cabeçalho ausente ou vazio é sempre recusado, qualquer que seja a chave esperada.
     */
    boolean isValid(String providedKey) {
        if (providedKey == null || providedKey.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(expectedKey, providedKey.getBytes(StandardCharsets.UTF_8));
    }

    /** Método e caminho como foram registrados (o mesmo formato do TokenFilter), ou null. */
    private static String matchedRoute(Context ctx) {
        Endpoint matched = ctx.endpoints().matchedHttpEndpoint();
        return matched == null ? null : matched.method.name() + " " + matched.path;
    }
}
