package com.lemma.core;

import com.lemma.core.client.AddressHistoryClient;
import com.lemma.core.client.BlockchainClient;
import com.lemma.core.client.HttpBlockchainClient;
import com.lemma.core.client.MarketDataClient;
import com.lemma.core.config.AppConfig;
import com.lemma.core.config.JsonConfig;
import com.lemma.core.controller.AuthController;
import com.lemma.core.controller.ErrorHandler;
import com.lemma.core.controller.MarketController;
import com.lemma.core.controller.TransactionController;
import com.lemma.core.controller.UserController;
import com.lemma.core.controller.WalletController;
import com.lemma.core.controller.WebhookController;
import com.lemma.core.ratelimit.SlidingWindowRateLimiter;
import com.lemma.core.repository.Database;
import com.lemma.core.repository.EthTransactionRepository;
import com.lemma.core.repository.UserRepository;
import com.lemma.core.security.CorsFilter;
import com.lemma.core.security.JwtService;
import com.lemma.core.security.PasswordHasher;
import com.lemma.core.security.ServiceKeyFilter;
import com.lemma.core.security.TokenFilter;
import com.lemma.core.service.AuthService;
import com.lemma.core.service.MarketService;
import com.lemma.core.service.TransactionService;
import com.lemma.core.service.UserService;
import com.lemma.core.service.WalletService;
import com.lemma.core.socket.TaskNoticeListener;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import org.hibernate.SessionFactory;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Ponto de entrada do Core API.
 *
 * Lê a configuração, abre o acesso ao banco (o Hibernate confere o esquema) e sobe
 * o Javalin. Se faltar variável, alguma for inválida, o banco estiver fora ou o
 * esquema não bater com as entidades, encerra com código 1 e uma mensagem clara,
 * antes de abrir a porta. As peças são montadas à mão aqui, sem injeção de dependência.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        AppConfig config;
        try {
            config = AppConfig.fromEnv(System.getenv());
        } catch (IllegalStateException e) {
            log.error("Configuração inválida: {}", e.getMessage());
            System.exit(1);
            return;
        }

        SessionFactory sessionFactory;
        try {
            sessionFactory = Database.createSessionFactory(config);
        } catch (RuntimeException e) {
            logDatabaseStartupFailure(e);
            System.exit(1);
            return;
        }

        // Montagem manual: repository e client → service → controller.
        UserRepository userRepository = new UserRepository(sessionFactory);
        EthTransactionRepository transactionRepository = new EthTransactionRepository(sessionFactory);
        PasswordHasher passwordHasher = new PasswordHasher(PasswordHasher.DEFAULT_COST);
        BlockchainClient blockchainClient = new HttpBlockchainClient(config.blockchainServiceUrl(),
                config.serviceKey(), Duration.ofSeconds(config.blockchainTimeoutSeconds()));
        MarketDataClient marketDataClient = new MarketDataClient(config.externalDataServiceUrl(),
                config.serviceKey(), Duration.ofSeconds(config.externalDataTimeoutSeconds()));
        AddressHistoryClient addressHistoryClient = new AddressHistoryClient(config.externalDataServiceUrl(),
                config.serviceKey(), Duration.ofSeconds(config.externalDataTimeoutSeconds()));
        JwtService jwtService = new JwtService(config.jwtSecret(), config.jwtTtlMinutes(), Clock.systemUTC());
        UserService userService = new UserService(userRepository, passwordHasher, blockchainClient);
        AuthService authService = new AuthService(userRepository, passwordHasher, jwtService);
        WalletService walletService = new WalletService(userRepository, blockchainClient, addressHistoryClient);
        TransactionService transactionService = new TransactionService(userRepository, transactionRepository,
                blockchainClient, Clock.systemUTC());
        SlidingWindowRateLimiter marketRateLimiter = new SlidingWindowRateLimiter(config.marketRateLimitMax(),
                Duration.ofSeconds(config.marketRateLimitWindowSeconds()), Clock.systemUTC());
        MarketService marketService = new MarketService(marketDataClient, marketRateLimiter);
        Controllers controllers = new Controllers(
                new UserController(userService),
                new AuthController(authService),
                new WalletController(walletService),
                new TransactionController(transactionService),
                new WebhookController(transactionService),
                new MarketController(marketService));

        Javalin app = createApp(config.corsAllowedOrigin(), jwtService, config.serviceKey(), controllers)
                .start(config.serverPort());

        // Canal de socket do Blockchain Service (CT27), só depois de o Javalin estar no ar.
        TaskNoticeListener taskNoticeListener = startTaskNoticeListener(config, transactionService);

        // Desligamento limpo: o docker stop manda SIGTERM, a JVM roda este gancho,
        // o Javalin termina as requisições em andamento e só depois o banco é fechado.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Desligando o Core API");
            if (taskNoticeListener != null) {
                taskNoticeListener.shutdown();
            }
            app.stop();
            sessionFactory.close();
        }));

        log.info("Core API no ar na porta {} com {}", config.serverPort(), config);
    }

    /**
     * Sobe a thread que ouve os avisos de task concluída pelo socket (CT27), com a
     * mesma SERVICE_KEY do webhook. TASK_SOCKET_PORT=0 desativa: devolve null.
     */
    static TaskNoticeListener startTaskNoticeListener(AppConfig config, TransactionService transactionService) {
        if (config.taskSocketPort() == 0) {
            log.info("socket: canal de avisos desativado (TASK_SOCKET_PORT=0)");
            return null;
        }
        TaskNoticeListener listener = new TaskNoticeListener(config.taskSocketHost(), config.taskSocketPort(),
                config.serviceKey(), transactionService, TaskNoticeListener.RETRY_DELAY);
        listener.start();
        return listener;
    }

    /** Os controllers montados, para não passar um parâmetro por controller. */
    public record Controllers(UserController user, AuthController auth, WalletController wallet,
                              TransactionController transaction, WebhookController webhook,
                              MarketController market) {
    }

    /**
     * Rotas sem nenhuma credencial (premissa P1), no formato "MÉTODO /caminho"
     * como foram registradas. Só o cadastro e o login; o MainRoutesTest fixa a lista.
     */
    static final Set<String> OPEN_ROUTES = Set.of(
            "POST /users",          // CT1
            "POST /auth/login");    // CT3

    /** Rotas entre serviços: sem token, com X-Service-Key (ServiceKeyFilter). */
    static final Set<String> SERVICE_KEY_ROUTES = Set.of(
            "POST /webhooks/tasks"); // CT15

    /**
     * Rotas que o TokenFilter dispensa: a união das duas listas acima, calculada e
     * não digitada. Uma rota só fica sem token se estiver aberta de propósito
     * (OPEN_ROUTES) ou exigir a chave de serviço (SERVICE_KEY_ROUTES). Toda outra
     * exige token: uma rota nova esquecida fica protegida, nunca aberta.
     */
    static final Set<String> PUBLIC_ROUTES = Stream.concat(OPEN_ROUTES.stream(), SERVICE_KEY_ROUTES.stream())
            .collect(Collectors.toUnmodifiableSet());

    /** Cria o Javalin com a configuração de {@link #configure}, sem abrir a porta. */
    public static Javalin createApp(String corsAllowedOrigin, JwtService jwtService, String serviceKey,
                                    Controllers controllers) {
        return Javalin.create(javalin -> configure(javalin, corsAllowedOrigin, jwtService, serviceKey, controllers));
    }

    /**
     * Configura JSON, handler de erros, filtros e rotas. Usado pelo main e pelos
     * testes HTTP, que assim cobrem a mesma configuração (e podem acrescentar
     * rotas próprias depois).
     *
     * O Javalin roda as fases nesta ordem, qualquer que seja a ordem de registro:
     * before (CORS) → beforeMatched (token, chave de serviço) → rota (ou 404/405) → after.
     */
    public static void configure(JavalinConfig javalin, String corsAllowedOrigin, JwtService jwtService,
                                 String serviceKey, Controllers controllers) {
        javalin.jsonMapper(JsonConfig.jsonMapper());
        // Rota existente com método errado responde 405, não 404.
        javalin.http.prefer405over404 = true;
        ErrorHandler.register(javalin);

        // CORS antes de tudo: responde a pré-requisição OPTIONS (que vem sem token)
        // e marca a origem permitida em toda resposta, inclusive nos erros.
        CorsFilter corsFilter = new CorsFilter(corsAllowedOrigin);
        javalin.routes.before(corsFilter::handle);

        // Token: beforeMatched só roda quando a rota existe, então 404 e 405 ficam como estão.
        TokenFilter tokenFilter = new TokenFilter(jwtService, PUBLIC_ROUTES);
        javalin.routes.beforeMatched(tokenFilter::handle);

        // Chave de serviço: só nas rotas de SERVICE_KEY_ROUTES (o TokenFilter as dispensa).
        ServiceKeyFilter serviceKeyFilter = new ServiceKeyFilter(serviceKey, SERVICE_KEY_ROUTES);
        javalin.routes.beforeMatched(serviceKeyFilter::handle);

        // Abertas (estão em OPEN_ROUTES).
        javalin.routes.post("/users", controllers.user()::register);      // CT1
        javalin.routes.post("/auth/login", controllers.auth()::login);    // CT3

        // Com token (fora de PUBLIC_ROUTES).
        javalin.routes.get("/wallet/balance", controllers.wallet()::balance);              // CT6
        javalin.routes.get("/wallet/transactions", controllers.wallet()::transactions);    // CT26
        javalin.routes.post("/transactions", controllers.transaction()::send);             // CT9
        javalin.routes.get("/transactions/{taskId}", controllers.transaction()::get);      // CT13
        javalin.routes.get("/market/{assetId}", controllers.market()::get);                // CT17

        // Entre serviços, com chave de serviço (estão em SERVICE_KEY_ROUTES).
        javalin.routes.post("/webhooks/tasks", controllers.webhook()::notifyTaskCompleted); // CT15
    }

    /**
     * Registra só a classe da exceção: a mensagem do driver pode trazer o host,
     * o usuário do banco ou a URL JDBC.
     */
    private static void logDatabaseStartupFailure(RuntimeException e) {
        if (hasCause(e, SchemaManagementException.class)) {
            log.error("Falha ao iniciar o acesso ao banco ({}): o esquema do banco não bate com as entidades"
                    + " (Hibernate validate); confira o schema.sql", e.getClass().getSimpleName());
        } else {
            log.error("Falha ao iniciar o acesso ao banco ({}): não foi possível conectar ao banco;"
                    + " confira o core-db e DB_URL, DB_USER e DB_PASSWORD", e.getClass().getSimpleName());
        }
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }
}
