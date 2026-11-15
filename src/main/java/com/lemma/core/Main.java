package com.lemma.core;

import com.lemma.core.client.BlockchainClient;
import com.lemma.core.client.HttpBlockchainClient;
import com.lemma.core.config.AppConfig;
import com.lemma.core.config.JsonConfig;
import com.lemma.core.controller.ErrorHandler;
import com.lemma.core.controller.UserController;
import com.lemma.core.repository.Database;
import com.lemma.core.repository.UserRepository;
import com.lemma.core.security.PasswordHasher;
import com.lemma.core.service.UserService;
import io.javalin.Javalin;
import org.hibernate.SessionFactory;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

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
        UserService userService = new UserService(userRepository, passwordHasher, blockchainClient);
        UserController userController = new UserController(userService);

        Javalin app = createApp(userController).start(config.serverPort());

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
     * Configura o Javalin com JSON, handler de erros e rotas, sem abrir a porta.
     * Usado pelo main e pelo teste HTTP, que assim cobre a mesma configuração.
     */
    public static Javalin createApp(UserController userController) {
        return Javalin.create(javalin -> {
            javalin.jsonMapper(JsonConfig.jsonMapper());
            // Rota existente com método errado responde 405, não 404.
            javalin.http.prefer405over404 = true;
            ErrorHandler.register(javalin);

            // Públicas (sem token).
            javalin.routes.post("/users", userController::register); // CT1
        });
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
