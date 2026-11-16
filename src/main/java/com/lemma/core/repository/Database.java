package com.lemma.core.repository;

import com.lemma.core.config.AppConfig;
import com.lemma.core.model.EthTransaction;
import com.lemma.core.model.User;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;

/**
 * Cria a SessionFactory do Hibernate a partir do AppConfig.
 *
 * O esquema vem do schema.sql; o Hibernate só confere (validate) que as entidades
 * batem com as tabelas e falha na subida se não baterem ou se o banco estiver fora.
 */
public final class Database {

    /**
     * Quanto uma requisição espera por uma conexão do pool (o padrão do Hikari é 30 s).
     * Com o banco fora, o cadastro responde 500 e a subida falha em até 10 s.
     */
    static final String CONNECTION_TIMEOUT_MILLIS = "10000";

    private Database() {
    }

    public static SessionFactory createSessionFactory(AppConfig config) {
        return new Configuration()
                .addAnnotatedClass(User.class)
                .addAnnotatedClass(EthTransaction.class)
                .setProperty(AvailableSettings.JAKARTA_JDBC_URL, config.dbUrl())
                .setProperty(AvailableSettings.JAKARTA_JDBC_USER, config.dbUser())
                .setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, config.dbPassword())
                .setProperty(AvailableSettings.HBM2DDL_AUTO, "validate")
                // Datas sempre em UTC; Instant gravado como DATETIME, como no schema.sql.
                .setProperty(AvailableSettings.JDBC_TIME_ZONE, "UTC")
                .setProperty(AvailableSettings.PREFERRED_INSTANT_JDBC_TYPE, "TIMESTAMP")
                // Pool de conexões HikariCP, pequeno: um único serviço com pouco tráfego.
                .setProperty(AvailableSettings.CONNECTION_PROVIDER,
                        "org.hibernate.hikaricp.internal.HikariCPConnectionProvider")
                .setProperty("hibernate.hikari.maximumPoolSize", "5")
                .setProperty("hibernate.hikari.connectionTimeout", CONNECTION_TIMEOUT_MILLIS)
                .buildSessionFactory();
    }
}
