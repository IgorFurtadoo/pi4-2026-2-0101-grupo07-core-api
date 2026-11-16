package com.lemma.core;

import com.lemma.core.controller.AuthController;
import com.lemma.core.controller.UserController;
import com.lemma.core.security.JwtService;
import com.lemma.core.security.PasswordHasher;
import com.lemma.core.service.AuthService;
import com.lemma.core.service.FakeBlockchainClient;
import com.lemma.core.service.InMemoryUserRepository;
import com.lemma.core.service.UserService;
import io.javalin.Javalin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Sobe o Core API numa porta livre com a mesma configuração do Main
 * (Main.createApp), services reais e dependências falsas: repository em memória,
 * Blockchain Service falso, bcrypt de custo 4, segredo de JWT de teste e relógio
 * fixo. Usado pelos testes HTTP.
 */
public final class TestApp {

    /** Segredo obviamente falso, só para os testes (mínimo de 32 bytes, como na produção). */
    public static final String JWT_SECRET = "segredo-de-teste-falso-com-mais-de-32-bytes";
    public static final int JWT_TTL_MINUTES = 240;
    /** Relógio fixo: o token emitido agora vence às 18:30, como no exemplo do CT3. */
    public static final Instant NOW = Instant.parse("2026-10-20T14:30:00Z");

    private static final HttpClient http = HttpClient.newHttpClient();

    public final InMemoryUserRepository repository = new InMemoryUserRepository();
    public final FakeBlockchainClient blockchain = new FakeBlockchainClient();
    public final PasswordHasher hasher = new PasswordHasher(4);
    public final JwtService jwtService = new JwtService(JWT_SECRET, JWT_TTL_MINUTES, Clock.fixed(NOW, ZoneOffset.UTC));

    private Javalin app;

    public TestApp start() {
        UserService userService = new UserService(repository, hasher, blockchain);
        AuthService authService = new AuthService(repository, hasher, jwtService);
        app = Main.createApp(new UserController(userService), new AuthController(authService)).start(0);
        return this;
    }

    public void stop() {
        app.stop();
    }

    /** Requisição para o caminho no servidor de teste; quem chama escolhe método, cabeçalhos e corpo. */
    public HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path));
    }

    public HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** POST com corpo JSON. */
    public HttpResponse<String> postJson(String path, String body) throws IOException, InterruptedException {
        return send(request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    /** O valor de "code" num corpo de erro { "code": "...", "message": "..." }. */
    public static String code(HttpResponse<String> response) {
        String body = response.body();
        int start = body.indexOf("\"code\":\"") + "\"code\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }
}
