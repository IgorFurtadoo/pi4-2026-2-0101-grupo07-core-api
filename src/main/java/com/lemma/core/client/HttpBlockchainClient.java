package com.lemma.core.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Cliente HTTP do Blockchain Service (seção 7.1 do desenho), com o HttpClient do JDK.
 *
 * Toda chamada leva X-Service-Key. Tempo limite de conexão de 3 s e de resposta
 * configurável (BLOCKCHAIN_TIMEOUT_SECONDS). Qualquer resposta que não seja a
 * esperada vira {@link BlockchainUnavailableException}.
 *
 * O log traz só o contrato e o status ou o code devolvido: nunca o corpo, a
 * X-Service-Key nem a URL. Das exceções de rede, só o nome da classe (a mensagem
 * pode trazer o host).
 */
public class HttpBlockchainClient implements BlockchainClient {

    private static final Logger log = LoggerFactory.getLogger(HttpBlockchainClient.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private static final String CT4 = "CT4 createWallet";

    /** Endereço Ethereum: 0x e 40 hexadecimais (cabe no VARCHAR(42) do app_user). */
    private static final Pattern ADDRESS = Pattern.compile("^0x[0-9a-fA-F]{40}$");

    /** Só um code nesse formato vai ao log; qualquer outra coisa do corpo, nunca. */
    private static final Pattern ERROR_CODE = Pattern.compile("^[A-Z_]{1,64}$");

    private final String baseUrl;
    private final String serviceKey;
    private final Duration responseTimeout;
    private final HttpClient httpClient;

    /** baseUrl sem barra final (o AppConfig já remove). */
    public HttpBlockchainClient(String baseUrl, String serviceKey, Duration responseTimeout) {
        this.baseUrl = baseUrl;
        this.serviceKey = serviceKey;
        this.responseTimeout = responseTimeout;
        // HTTP/1.1: sem tentativa de upgrade para HTTP/2 em http:// simples.
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /** CT4: POST /wallets sem corpo → 201 { "ethereumAddress": "0x..." }. */
    @Override
    public String createWallet() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/wallets"))
                .timeout(responseTimeout)
                .header("X-Service-Key", serviceKey)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = send(CT4, request);

        if (response.statusCode() != 201) {
            logUnexpectedStatus(CT4, response);
            throw new BlockchainUnavailableException(CT4);
        }
        String address = readTextField(response.body(), "ethereumAddress");
        if (address == null || !ADDRESS.matcher(address).matches()) {
            log.warn("{}: HTTP 201 sem ethereumAddress válido no corpo", CT4);
            throw new BlockchainUnavailableException(CT4);
        }
        return address;
    }

    private HttpResponse<String> send(String contract, HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // devolve o pedido de interrupção a quem chamou
            log.warn("{}: chamada interrompida", contract);
            throw new BlockchainUnavailableException(contract);
        } catch (IOException e) {
            // Conexão recusada, host inexistente, tempo esgotado (HttpTimeoutException).
            log.warn("{}: falha de rede ({})", contract, e.getClass().getSimpleName());
            throw new BlockchainUnavailableException(contract);
        }
    }

    /** SERVICE_KEY_INVALID é erro de configuração (chaves diferentes nos dois módulos): ERROR. */
    private static void logUnexpectedStatus(String contract, HttpResponse<String> response) {
        String code = readTextField(response.body(), "code");
        if (code == null || !ERROR_CODE.matcher(code).matches()) {
            code = "sem code";
        }
        if ("SERVICE_KEY_INVALID".equals(code)) {
            log.error("{}: HTTP {} {}; a SERVICE_KEY difere da configurada no Blockchain Service",
                    contract, response.statusCode(), code);
        } else {
            log.warn("{}: HTTP {} {}", contract, response.statusCode(), code);
        }
    }

    /** Lê um campo de texto do corpo JSON; devolve null se o corpo for ilegível ou o campo faltar. */
    private static String readTextField(String body, String field) {
        try {
            JsonNode node = JSON.readTree(body).path(field);
            return node.isTextual() ? node.asText() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
