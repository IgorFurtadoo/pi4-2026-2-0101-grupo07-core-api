package com.lemma.core.client;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * External Data Service falso (CT18) para os testes: servidor HTTP do próprio JDK
 * em 127.0.0.1, porta livre, sem rede externa.
 *
 * Responde a qualquer caminho com o status e o corpo programados por
 * {@link #reply(int, String)}, depois de esperar {@link #delay(long)}. O padrão é
 * 500 INTERNAL_ERROR, para nenhum teste passar sem programar a resposta.
 *
 * Guarda as requisições recebidas (método, caminho, query, se a X-Service-Key veio
 * igual à esperada e o tamanho do corpo). O valor da chave nunca é guardado, e o
 * falso não loga nada. Use com try-with-resources ou feche no fim do teste.
 */
public final class FakeExternalDataService implements AutoCloseable {

    /** Corpo do 404 do CT18 para ativo desconhecido. */
    public static final String ASSET_NOT_FOUND =
            "{\"code\":\"ASSET_NOT_FOUND\",\"message\":\"Ativo não encontrado.\"}";

    /** Série válida de três pontos, na ordem em que o falso a devolve. */
    public static final String VALID_SERIES = "[{\"timestamp\":\"2026-10-19T12:00:00Z\",\"price\":3187.9},"
            + "{\"timestamp\":\"2026-10-19T13:00:00Z\",\"price\":3190.12},"
            + "{\"timestamp\":\"2026-10-19T14:00:00Z\",\"price\":3201}]";

    /** Uma requisição recebida. serviceKeyOk: o cabeçalho veio e é igual à chave esperada. */
    public record Received(String method, String rawPath, String rawQuery, boolean serviceKeyOk, int bodyLength) {
    }

    private final HttpServer server;
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private final List<Received> requests = new CopyOnWriteArrayList<>();

    private volatile int status = 500;
    private volatile String body = "{\"code\":\"INTERNAL_ERROR\",\"message\":\"Erro interno.\"}";
    private volatile long delayMillis;

    public FakeExternalDataService(String expectedServiceKey) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(threads);
        server.createContext("/", exchange -> {
            byte[] requestBody;
            try (InputStream in = exchange.getRequestBody()) {
                requestBody = in.readAllBytes();
            }
            requests.add(new Received(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(),
                    expectedServiceKey.equals(exchange.getRequestHeaders().getFirst("X-Service-Key")),
                    requestBody.length));
            // Lê o programado uma vez: um teste que muda a resposta no meio não mistura status e corpo.
            int replyStatus = status;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            long wait = delayMillis;
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    exchange.close();
                    return;
                }
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(replyStatus, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
    }

    /** Corpo 200 válido do CT18 para o assetId, com preço atual 3200.45 e {@link #VALID_SERIES}. */
    public static String validBody(String assetId) {
        return body(assetId, "USD", "3200.45", VALID_SERIES);
    }

    /** Corpo 200 do CT18 com os valores JSON dados como estão (texto entre aspas, número sem). */
    public static String body(String assetId, String currency, String currentPriceJson, String seriesJson) {
        return "{\"assetId\":\"" + assetId + "\",\"currency\":\"" + currency + "\",\"currentPrice\":"
                + currentPriceJson + ",\"series\":" + seriesJson + "}";
    }

    /** Programa a resposta de todas as requisições seguintes. */
    public FakeExternalDataService reply(int status, String body) {
        this.status = status;
        this.body = body;
        return this;
    }

    /** Espera antes de responder, para os testes de tempo esgotado. */
    public FakeExternalDataService delay(long millis) {
        this.delayMillis = millis;
        return this;
    }

    /** Cópia das requisições recebidas, na ordem. */
    public List<Received> requests() {
        return List.copyOf(requests);
    }

    /** Base sem barra final, como o AppConfig entrega. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Para o servidor; o shutdownNow interrompe a resposta que estiver esperando. */
    @Override
    public void close() {
        server.stop(0);
        threads.shutdownNow();
    }
}
