package com.lemma.core.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigTest {

    // Valores só de teste, nunca usados fora daqui. O segredo tem exatamente 32 bytes.
    private static final String TEST_JWT_SECRET = "test-jwt-secret-0123456789abcdef";
    private static final String TEST_SERVICE_KEY = "test-service-key";
    private static final String TEST_DB_PASSWORD = "test-db-password";

    private static Map<String, String> validEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("SERVER_PORT", "8080");
        env.put("DB_URL", "jdbc:mysql://core-db:3306/core");
        env.put("DB_USER", "app");
        env.put("DB_PASSWORD", TEST_DB_PASSWORD);
        env.put("JWT_SECRET", TEST_JWT_SECRET);
        env.put("JWT_TTL_MINUTES", "240");
        env.put("SERVICE_KEY", TEST_SERVICE_KEY);
        env.put("BLOCKCHAIN_SERVICE_URL", "http://blockchain-service:8081");
        env.put("EXTERNAL_DATA_SERVICE_URL", "http://external-data-service:8082");
        env.put("BLOCKCHAIN_TIMEOUT_SECONDS", "15");
        env.put("EXTERNAL_DATA_TIMEOUT_SECONDS", "15");
        env.put("MARKET_RATE_LIMIT_MAX", "10");
        env.put("MARKET_RATE_LIMIT_WINDOW_SECONDS", "60");
        env.put("CORS_ALLOWED_ORIGIN", "http://localhost:5173");
        return env;
    }

    private static String failureMessage(Map<String, String> env) {
        return assertThrows(IllegalStateException.class, () -> AppConfig.fromEnv(env)).getMessage();
    }

    private static Map<String, String> envWith(String name, String value) {
        Map<String, String> env = validEnv();
        env.put(name, value);
        return env;
    }

    @Test
    void loadsValidConfiguration() {
        AppConfig config = AppConfig.fromEnv(validEnv());

        assertEquals(8080, config.serverPort());
        assertEquals(240, config.jwtTtlMinutes());
        assertEquals(15, config.blockchainTimeoutSeconds());
        assertEquals(10, config.marketRateLimitMax());
        assertEquals(60, config.marketRateLimitWindowSeconds());
        assertEquals("http://blockchain-service:8081", config.blockchainServiceUrl());
        assertEquals("http://localhost:5173", config.corsAllowedOrigin());
    }

    @Test
    void failsWhenVariableIsMissing() {
        Map<String, String> env = validEnv();
        env.remove("SERVICE_KEY");

        assertTrue(failureMessage(env).contains("variável obrigatória ausente: SERVICE_KEY"));
    }

    @Test
    void failsWhenVariableIsBlank() {
        assertTrue(failureMessage(envWith("DB_USER", "   ")).contains("variável obrigatória ausente: DB_USER"));
    }

    @Test
    void reportsAllMissingVariablesAtOnce() {
        Map<String, String> env = validEnv();
        env.remove("DB_URL");
        env.remove("CORS_ALLOWED_ORIGIN");
        env.remove("JWT_SECRET");

        String message = failureMessage(env);
        assertTrue(message.contains("DB_URL"));
        assertTrue(message.contains("CORS_ALLOWED_ORIGIN"));
        assertTrue(message.contains("JWT_SECRET"));
    }

    @Test
    void rejectsNonPositiveOrNonNumericValues() {
        Map<String, String> env = validEnv();
        env.put("MARKET_RATE_LIMIT_MAX", "0");
        env.put("JWT_TTL_MINUTES", "-5");
        env.put("BLOCKCHAIN_TIMEOUT_SECONDS", "abc");

        String message = failureMessage(env);
        assertTrue(message.contains("MARKET_RATE_LIMIT_MAX inválida"));
        assertTrue(message.contains("JWT_TTL_MINUTES inválida"));
        assertTrue(message.contains("BLOCKCHAIN_TIMEOUT_SECONDS inválida"));
    }

    @Test
    void rejectsPortOutOfRange() {
        assertTrue(failureMessage(envWith("SERVER_PORT", "70000")).contains("SERVER_PORT inválida"));
    }

    @Test
    void rejectsServiceUrlThatIsNotHttp() {
        Map<String, String> env = validEnv();
        env.put("BLOCKCHAIN_SERVICE_URL", "ftp://blockchain-service:8081");
        env.put("EXTERNAL_DATA_SERVICE_URL", "external-data-service:8082");

        String message = failureMessage(env);
        assertTrue(message.contains("BLOCKCHAIN_SERVICE_URL inválida"));
        assertTrue(message.contains("EXTERNAL_DATA_SERVICE_URL inválida"));
    }

    @Test
    void rejectsServiceUrlWithCredentialsOrQuery() {
        Map<String, String> env = validEnv();
        env.put("BLOCKCHAIN_SERVICE_URL", "http://user:pass@blockchain-service:8081");
        env.put("EXTERNAL_DATA_SERVICE_URL", "http://external-data-service:8082?key=x");

        String message = failureMessage(env);
        assertTrue(message.contains("BLOCKCHAIN_SERVICE_URL inválida"));
        assertTrue(message.contains("EXTERNAL_DATA_SERVICE_URL inválida"));
    }

    @Test
    void removesTrailingSlashFromServiceUrl() {
        AppConfig config = AppConfig.fromEnv(envWith("BLOCKCHAIN_SERVICE_URL", "http://blockchain-service:8081/"));

        assertEquals("http://blockchain-service:8081", config.blockchainServiceUrl());
    }

    @Test
    void rejectsInvalidCorsOrigin() {
        for (String origin : new String[] {"*", "http://localhost:5173/", "http://localhost:5173/app",
                "localhost:5173", "file:///tmp"}) {
            assertTrue(failureMessage(envWith("CORS_ALLOWED_ORIGIN", origin)).contains("CORS_ALLOWED_ORIGIN inválida"),
                    "deveria recusar a origem " + origin);
        }
    }

    @Test
    void acceptsHttpsCorsOriginWithoutPort() {
        AppConfig config = AppConfig.fromEnv(envWith("CORS_ALLOWED_ORIGIN", "https://lemma.example.com"));

        assertEquals("https://lemma.example.com", config.corsAllowedOrigin());
    }

    @Test
    void rejectsDbUrlThatIsNotJdbcMysql() {
        assertTrue(failureMessage(envWith("DB_URL", "http://core-db:3306/core")).contains("DB_URL inválida"));
        assertTrue(failureMessage(envWith("DB_URL", "jdbc:mysql://")).contains("DB_URL inválida"));
    }

    @Test
    void rejectsShortJwtSecret() {
        String shortSecret = TEST_JWT_SECRET.substring(1); // 31 bytes

        String message = failureMessage(envWith("JWT_SECRET", shortSecret));
        assertTrue(message.contains("JWT_SECRET inválida: precisa ter no mínimo 32 bytes"));
        assertFalse(message.contains(shortSecret));
    }

    @Test
    void countsJwtSecretInBytesNotCharacters() {
        // 16 caracteres "é" ocupam 32 bytes em UTF-8: aceito.
        AppConfig config = AppConfig.fromEnv(envWith("JWT_SECRET", "é".repeat(16)));
        assertEquals("é".repeat(16), config.jwtSecret());

        // 31 caracteres ASCII são 31 bytes: recusado.
        assertTrue(failureMessage(envWith("JWT_SECRET", "a".repeat(31))).contains("JWT_SECRET inválida"));
    }

    @Test
    void rejectsExampleJwtSecretEvenThoughItIsLongEnough() {
        assertTrue(AppConfig.EXAMPLE_JWT_SECRET.length() >= AppConfig.JWT_SECRET_MIN_BYTES);

        String message = failureMessage(envWith("JWT_SECRET", AppConfig.EXAMPLE_JWT_SECRET));
        assertTrue(message.contains("JWT_SECRET está com o valor de exemplo"));
        assertFalse(message.contains(AppConfig.EXAMPLE_JWT_SECRET));
    }

    @Test
    void rejectsExampleServiceKey() {
        String message = failureMessage(envWith("SERVICE_KEY", AppConfig.EXAMPLE_SERVICE_KEY));
        assertTrue(message.contains("SERVICE_KEY está com o valor de exemplo"));
        assertFalse(message.contains(AppConfig.EXAMPLE_SERVICE_KEY));
    }

    @Test
    void rejectsExampleDbPassword() {
        String message = failureMessage(envWith("DB_PASSWORD", AppConfig.EXAMPLE_DB_PASSWORD));
        assertTrue(message.contains("DB_PASSWORD está com o valor de exemplo"));
        assertFalse(message.contains(AppConfig.EXAMPLE_DB_PASSWORD));
    }

    @Test
    void errorMessageDoesNotExposeValues() {
        Map<String, String> env = validEnv();
        env.remove("DB_URL");
        env.put("BLOCKCHAIN_SERVICE_URL", "ftp://segredo-na-url:8081");

        String message = failureMessage(env);
        assertFalse(message.contains(TEST_DB_PASSWORD));
        assertFalse(message.contains(TEST_SERVICE_KEY));
        assertFalse(message.contains(TEST_JWT_SECRET));
        assertFalse(message.contains("segredo-na-url"));
    }

    @Test
    void toStringDoesNotExposeSecretsOrUrls() {
        Map<String, String> env = validEnv();
        env.put("DB_URL", "jdbc:mysql://core-db:3306/core?password=test-jdbc-password");

        String text = AppConfig.fromEnv(env).toString();
        assertFalse(text.contains(TEST_DB_PASSWORD));
        assertFalse(text.contains(TEST_SERVICE_KEY));
        assertFalse(text.contains(TEST_JWT_SECRET));
        assertFalse(text.contains("jdbc:mysql"));
        assertFalse(text.contains("test-jdbc-password"));
        assertFalse(text.contains("blockchain-service"));
        assertFalse(text.contains("external-data-service"));
    }

    @Test
    void taskSocketUsesDefaultsWhenAbsent() {
        AppConfig config = AppConfig.fromEnv(validEnv());

        assertEquals("blockchain-service", config.taskSocketHost());
        assertEquals(9091, config.taskSocketPort());
    }

    @Test
    void taskSocketAcceptsCustomHostAndPortZero() {
        Map<String, String> env = validEnv();
        env.put("TASK_SOCKET_HOST", " outro-host ");
        env.put("TASK_SOCKET_PORT", "0");

        AppConfig config = AppConfig.fromEnv(env);
        assertEquals("outro-host", config.taskSocketHost());
        assertEquals(0, config.taskSocketPort());
    }

    @Test
    void rejectsInvalidTaskSocketPort() {
        for (String value : new String[]{"-1", "65536", "abc"}) {
            assertEquals("TASK_SOCKET_PORT inválida: precisa estar entre 0 (desativa) e 65535",
                    failureMessage(envWith("TASK_SOCKET_PORT", value)), value);
        }
    }
}
