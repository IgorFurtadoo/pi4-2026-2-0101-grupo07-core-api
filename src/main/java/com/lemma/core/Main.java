package com.lemma.core;

import com.lemma.core.config.AppConfig;
import com.lemma.core.controller.ErrorHandler;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ponto de entrada do Core API.
 *
 * Lê a configuração e sobe o Javalin. Se faltar variável ou alguma for inválida,
 * encerra com código 1 e uma mensagem clara, antes de abrir a porta. As peças são
 * montadas à mão aqui, sem injeção de dependência. As rotas entram a partir do C2.
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

        Javalin app = Javalin.create(javalin -> {
            // Rota existente com método errado responde 405, não 404.
            javalin.http.prefer405over404 = true;
            ErrorHandler.register(javalin);
        }).start(config.serverPort());

        // Desligamento limpo: o docker stop manda SIGTERM, a JVM roda este gancho
        // e o Javalin termina as requisições em andamento antes de fechar a porta.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Desligando o Core API");
            app.stop();
        }));

        log.info("Core API no ar na porta {} com {}", config.serverPort(), config);
    }
}
