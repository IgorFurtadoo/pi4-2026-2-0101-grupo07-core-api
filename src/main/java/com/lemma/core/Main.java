package com.lemma.core;

import com.lemma.core.client.BlockchainClient;
import com.lemma.core.client.HttpBlockchainClient;
import com.lemma.core.config.AppConfig;
import com.lemma.core.config.JsonConfig;
import com.lemma.core.controller.AuthController;
import com.lemma.core.controller.ErrorHandler;
import com.lemma.core.controller.UserController;
import com.lemma.core.controller.WalletController;
import com.lemma.core.repository.Database;
import com.lemma.core.repository.UserRepository;
import com.lemma.core.security.CorsFilter;
import com.lemma.core.security.JwtService;
import com.lemma.core.security.PasswordHasher;
import com.lemma.core.security.TokenFilter;
import com.lemma.core.service.AuthService;
import com.lemma.core.service.UserService;
import com.lemma.core.service.WalletService;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import org.hibernate.SessionFactory;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;

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
        PasswordHasher passwordHasher = new PasswordHasher(PasswordHasher.DEFAULT_COST);
        BlockchainClient blockchainClient = new HttpBlockchainClient(config.blockchainServiceUrl(),
                config.serviceKey(), Duration.ofSeconds(config.blockchainTimeoutSeconds()));
        JwtService jwtService = new JwtService(config.jwtSecret(), config.jwtTtlMinutes(), Clock.systemUTC());
        UserService userService = new UserService(userRepository, passwordHasher, blockchainClient);
        AuthService authService = new AuthService(userRepository, passwordHasher, jwtService);
        WalletService walletService = new WalletService(userRepository, blockchainClient);
        UserController userController = new UserController(userService);
        AuthController authController = new AuthController(authService);
        WalletController walletController = new WalletController(walletService);

        Javalin app = createApp(config.corsAllowedOrigin(), jwtService, userController, authController,
                walletController).start(config.serverPort());

        // Desligamento limpo: o docker stop manda SIGTERM, a JVM roda este gancho,
        // o Javalin termina as requisições em andamento e só depois o banco é fechado.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Desligando o Core API");
            app.stop();
            sessionFactory.close();
        }));

        log.info("Core API no ar na porta {} com {}", config.serverPort(), config);
    }

    /**
     * Rotas que não exigem token, no formato "MÉTODO /caminho" como foram
     * registradas. Toda outra rota exige token (TokenFilter): uma rota nova só
     * fica pública se entrar aqui.
     */
    static final Set<String> PUBLIC_ROUTES = Set.of(
            "POST /users",          // CT1
            "POST /auth/login");    // CT3

    /** Cria o Javalin com a configuração de {@link #configure}, sem abrir a porta. */
    public static Javalin createApp(String corsAllowedOrigin, JwtService jwtService,
                                    UserController userController, AuthController authController,
                                    WalletController walletController) {
        return Javalin.create(javalin -> configure(javalin, corsAllowedOrigin, jwtService,
                userController, authController, walletController));
    }

    /**
     * Configura JSON, handler de erros, filtros e rotas. Usado pelo main e pelos
     * testes HTTP, que assim cobrem a mesma configuração (e podem acrescentar
     * rotas próprias depois).
     *
     * O Javalin roda as fases nesta ordem, qualquer que seja a ordem de registro:
     * before (CORS) → beforeMatched (token) → rota (ou 404/405) → after.
     */
    public static void configure(JavalinConfig javalin, String corsAllowedOrigin, JwtService jwtService,
                                 UserController userController, AuthController authController,
                                 WalletController walletController) {
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

        // Públicas (estão em PUBLIC_ROUTES).
        javalin.routes.post("/users", userController::register);      // CT1
        javalin.routes.post("/auth/login", authController::login);    // CT3

        // Com token (fora de PUBLIC_ROUTES).
        javalin.routes.get("/wallet/balance", walletController::balance);    // CT6
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
