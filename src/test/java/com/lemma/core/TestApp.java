package com.lemma.core;

import com.lemma.core.client.FakeExternalDataService;
import com.lemma.core.client.MarketDataClient;
import com.lemma.core.controller.AuthController;
import com.lemma.core.controller.MarketController;
import com.lemma.core.controller.TransactionController;
import com.lemma.core.controller.UserController;
import com.lemma.core.controller.WalletController;
import com.lemma.core.controller.WebhookController;
import com.lemma.core.ratelimit.SlidingWindowRateLimiter;
import com.lemma.core.security.JwtService;
import com.lemma.core.security.PasswordHasher;
import com.lemma.core.service.AuthService;
import com.lemma.core.service.FakeBlockchainClient;
import com.lemma.core.service.InMemoryEthTransactionRepository;
import com.lemma.core.service.InMemoryUserRepository;
import com.lemma.core.service.MarketService;
import com.lemma.core.service.TransactionService;
import com.lemma.core.service.UserService;
import com.lemma.core.service.WalletService;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Consumer;

/**
 * Sobe o Core API numa porta livre com a mesma configuração do Main
 * (Main.configure), services reais e dependências falsas: repositories em memória,
 * Blockchain Service falso, External Data Service falso (servidor HTTP do JDK, o
 * mesmo MarketDataClient do Main), bcrypt de custo 4, segredo de JWT e chave de
 * serviço de teste e relógio fixo. O limite do mercado usa um relógio de teste
 * próprio (marketClock), que só anda quando o teste manda. Usado pelos testes HTTP.
 */
public final class TestApp {

    /** Segredo obviamente falso, só para os testes (mínimo de 32 bytes, como na produção). */
    public static final String JWT_SECRET = "segredo-de-teste-falso-com-mais-de-32-bytes";
    public static final int JWT_TTL_MINUTES = 240;
    /** Relógio fixo: o token emitido agora vence às 18:30, como no exemplo do CT3. */
    public static final Instant NOW = Instant.parse("2026-10-20T14:30:00Z");
    /** Origem do Frontend permitida pelo CORS (o valor do .env.example). */
    public static final String CORS_ORIGIN = "http://localhost:5173";
    /** Chave de serviço obviamente falsa, só para os testes. */
    public static final String SERVICE_KEY = "chave-de-servico-de-teste-falsa";
    /** Limite padrão do mercado: alto o bastante para nenhum teste que não seja de limite esbarrar nele. */
    public static final int DEFAULT_MARKET_RATE_LIMIT_MAX = 1_000;
    public static final Duration DEFAULT_MARKET_RATE_LIMIT_WINDOW = Duration.ofSeconds(60);

    private static final HttpClient http = HttpClient.newHttpClient();

    public final InMemoryUserRepository repository = new InMemoryUserRepository();
    public final InMemoryEthTransactionRepository transactions = new InMemoryEthTransactionRepository();
    public final FakeBlockchainClient blockchain = new FakeBlockchainClient();
    /** External Data Service falso; recebe a mesma chave de serviço, como no Main. Fechado no stop. */
    public final FakeExternalDataService marketData = new FakeExternalDataService(SERVICE_KEY);
    public final PasswordHasher hasher = new PasswordHasher(4);
    public final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    public final JwtService jwtService = new JwtService(JWT_SECRET, JWT_TTL_MINUTES, clock);
    /** Relógio do limite do mercado, separado do clock fixo do JWT e das transações. */
    public final TestClock marketClock = new TestClock(NOW);

    private final int marketRateLimitMax;
    private final Duration marketRateLimitWindow;
    private Javalin app;

    /** Com o limite padrão do mercado ({@link #DEFAULT_MARKET_RATE_LIMIT_MAX} por 60 s). */
    public TestApp() {
        this(DEFAULT_MARKET_RATE_LIMIT_MAX, DEFAULT_MARKET_RATE_LIMIT_WINDOW);
    }

    /** Com um limite do mercado próprio, para os testes de limite. */
    public TestApp(int marketRateLimitMax, Duration marketRateLimitWindow) {
        this.marketRateLimitMax = marketRateLimitMax;
        this.marketRateLimitWindow = marketRateLimitWindow;
    }

    public TestApp start() {
        return start(javalin -> {
        });
    }

    /**
     * Sobe com a configuração do Main (Main.configure) e, depois dela, as rotas que
     * só existem no teste (por exemplo, uma rota protegida de teste, sem banco nem Blockchain Service).
     */
    public TestApp start(Consumer<JavalinConfig> extraRoutes) {
        UserService userService = new UserService(repository, hasher, blockchain);
        AuthService authService = new AuthService(repository, hasher, jwtService);
        WalletService walletService = new WalletService(repository, blockchain);
        TransactionService transactionService = new TransactionService(repository, transactions, blockchain, clock);
        MarketService marketService = new MarketService(
                new MarketDataClient(marketData.baseUrl(), SERVICE_KEY, Duration.ofSeconds(2)),
                new SlidingWindowRateLimiter(marketRateLimitMax, marketRateLimitWindow, marketClock));
        Main.Controllers controllers = new Main.Controllers(
                new UserController(userService),
                new AuthController(authService),
                new WalletController(walletService),
                new TransactionController(transactionService),
                new WebhookController(transactionService),
                new MarketController(marketService));
        app = Javalin.create(javalin -> {
            Main.configure(javalin, CORS_ORIGIN, jwtService, SERVICE_KEY, controllers);
            extraRoutes.accept(javalin);
        }).start(0);
        return this;
    }

    public void stop() {
        app.stop();
        marketData.close();
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
