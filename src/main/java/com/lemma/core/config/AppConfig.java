package com.lemma.core.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Configuração do serviço, lida das variáveis de ambiente (seção 3.2 do desenho).
 *
 * Todas as variáveis são obrigatórias. Se faltar alguma ou o valor for inválido,
 * {@link #fromEnv(Map)} lança {@link IllegalStateException} listando todos os
 * problemas de uma vez. A mensagem cita só o nome da variável, nunca o valor,
 * para não vazar segredo nem URL em log.
 */
public record AppConfig(
        int serverPort,
        String dbUrl,
        String dbUser,
        String dbPassword,
        String jwtSecret,
        int jwtTtlMinutes,
        String serviceKey,
        String blockchainServiceUrl,
        String externalDataServiceUrl,
        int blockchainTimeoutSeconds,
        int externalDataTimeoutSeconds,
        int marketRateLimitMax,
        int marketRateLimitWindowSeconds,
        String corsAllowedOrigin) {

    /** HS256 com segredo de no mínimo 32 bytes (seção 9 do desenho). */
    static final int JWT_SECRET_MIN_BYTES = 32;

    // Valores de exemplo do .env.example. São recusados para o serviço nunca
    // subir com um segredo que está publicado no repositório.
    static final String EXAMPLE_JWT_SECRET = "TROCAR_gere_com_openssl_rand_-base64_48";
    static final String EXAMPLE_SERVICE_KEY = "TROCAR_mesmo_valor_do_blockchain_service";
    static final String EXAMPLE_DB_PASSWORD = "TROCAR_senha_do_banco";

    private static final int MAX_PORT = 65535;

    public static AppConfig fromEnv(Map<String, String> env) {
        Reader reader = new Reader(env);

        int serverPort = reader.port("SERVER_PORT");
        String dbUrl = reader.jdbcUrl("DB_URL");
        String dbUser = reader.text("DB_USER");
        String dbPassword = reader.secret("DB_PASSWORD", EXAMPLE_DB_PASSWORD,
                "troque pela senha do banco");
        String jwtSecret = reader.jwtSecret("JWT_SECRET");
        int jwtTtlMinutes = reader.positiveInt("JWT_TTL_MINUTES");
        String serviceKey = reader.secret("SERVICE_KEY", EXAMPLE_SERVICE_KEY,
                "use o mesmo valor configurado no Blockchain Service");
        String blockchainServiceUrl = reader.httpUrl("BLOCKCHAIN_SERVICE_URL");
        String externalDataServiceUrl = reader.httpUrl("EXTERNAL_DATA_SERVICE_URL");
        int blockchainTimeoutSeconds = reader.positiveInt("BLOCKCHAIN_TIMEOUT_SECONDS");
        int externalDataTimeoutSeconds = reader.positiveInt("EXTERNAL_DATA_TIMEOUT_SECONDS");
        int marketRateLimitMax = reader.positiveInt("MARKET_RATE_LIMIT_MAX");
        int marketRateLimitWindowSeconds = reader.positiveInt("MARKET_RATE_LIMIT_WINDOW_SECONDS");
        String corsAllowedOrigin = reader.origin("CORS_ALLOWED_ORIGIN");

        if (!reader.problems.isEmpty()) {
            throw new IllegalStateException(String.join("; ", reader.problems));
        }

        return new AppConfig(serverPort, dbUrl, dbUser, dbPassword, jwtSecret, jwtTtlMinutes,
                serviceKey, blockchainServiceUrl, externalDataServiceUrl,
                blockchainTimeoutSeconds, externalDataTimeoutSeconds,
                marketRateLimitMax, marketRateLimitWindowSeconds, corsAllowedOrigin);
    }

    /**
     * Não imprime segredos (senha do banco, segredo do JWT, chave de serviço) nem URLs:
     * uma URL JDBC pode carregar credencial, e as URLs dos outros módulos não vão ao log.
     */
    @Override
    public String toString() {
        return "AppConfig[serverPort=" + serverPort
                + ", dbUser=" + dbUser
                + ", jwtTtlMinutes=" + jwtTtlMinutes
                + ", blockchainTimeoutSeconds=" + blockchainTimeoutSeconds
                + ", externalDataTimeoutSeconds=" + externalDataTimeoutSeconds
                + ", marketRateLimitMax=" + marketRateLimitMax
                + ", marketRateLimitWindowSeconds=" + marketRateLimitWindowSeconds
                + ", corsAllowedOrigin=" + corsAllowedOrigin + "]";
    }

    /** Lê cada variável e acumula os problemas encontrados, sem parar no primeiro. */
    private static final class Reader {
        private final Map<String, String> env;
        private final List<String> problems = new ArrayList<>();

        Reader(Map<String, String> env) {
            this.env = env;
        }

        String text(String name) {
            String value = env.get(name);
            if (value == null || value.isBlank()) {
                problems.add("variável obrigatória ausente: " + name);
                return null;
            }
            return value.trim();
        }

        int positiveInt(String name) {
            String value = text(name);
            if (value == null) {
                return 0;
            }
            try {
                int number = Integer.parseInt(value);
                if (number > 0) {
                    return number;
                }
            } catch (NumberFormatException e) {
                // cai na mensagem abaixo
            }
            problems.add(name + " inválida: precisa ser um número inteiro maior que zero");
            return 0;
        }

        int port(String name) {
            int number = positiveInt(name);
            if (number > MAX_PORT) {
                problems.add(name + " inválida: precisa estar entre 1 e " + MAX_PORT);
                return 0;
            }
            return number;
        }

        String jdbcUrl(String name) {
            String value = text(name);
            if (value == null) {
                return null;
            }
            if (!value.startsWith("jdbc:mysql://") || value.length() == "jdbc:mysql://".length()) {
                problems.add(name + " inválida: precisa começar com jdbc:mysql://");
                return null;
            }
            return value;
        }

        /** Segredo obrigatório que não pode ficar com o valor de exemplo do .env.example. */
        String secret(String name, String exampleValue, String hint) {
            String value = text(name);
            if (value == null) {
                return null;
            }
            if (value.equals(exampleValue)) {
                problems.add(name + " está com o valor de exemplo do .env.example: " + hint);
                return null;
            }
            return value;
        }

        /** Os bytes UTF-8 da string são a chave do HMAC; por isso o mínimo é contado em bytes. */
        String jwtSecret(String name) {
            String value = secret(name, EXAMPLE_JWT_SECRET, "gere um com: openssl rand -base64 48");
            if (value == null) {
                return null;
            }
            if (value.getBytes(StandardCharsets.UTF_8).length < JWT_SECRET_MIN_BYTES) {
                problems.add(name + " inválida: precisa ter no mínimo " + JWT_SECRET_MIN_BYTES
                        + " bytes. Gere com: openssl rand -base64 48");
                return null;
            }
            return value;
        }

        /** Base de outro módulo: http ou https, com host, sem usuário, query nem fragmento. */
        String httpUrl(String name) {
            String value = text(name);
            if (value == null) {
                return null;
            }
            URI uri = parseHttpUri(value);
            if (uri == null) {
                problems.add(name + " inválida: precisa ser uma URL http:// ou https:// com host,"
                        + " sem usuário, query nem fragmento");
                return null;
            }
            // Sem barra final, para o cliente montar base + "/caminho".
            return value.replaceAll("/+$", "");
        }

        /** Origem do navegador: só esquema://host[:porta], como o cabeçalho Origin chega. */
        String origin(String name) {
            String value = text(name);
            if (value == null) {
                return null;
            }
            URI uri = parseHttpUri(value);
            if (uri == null || !uri.getRawPath().isEmpty()) {
                problems.add(name + " inválida: precisa ser uma origem como http://localhost:5173"
                        + " (http ou https, host e porta opcional, sem caminho nem barra final)");
                return null;
            }
            return value;
        }

        private static URI parseHttpUri(String value) {
            URI uri;
            try {
                uri = new URI(value);
            } catch (URISyntaxException e) {
                return null;
            }
            boolean http = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
            if (!http || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return null;
            }
            return uri;
        }
    }
}
