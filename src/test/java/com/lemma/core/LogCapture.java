package com.lemma.core;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Captura o log de uma classe nos testes, com o ListAppender do próprio Logback.
 * Use com try-with-resources para soltar o appender no fim.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public LogCapture(Class<?> type) {
        this.logger = (Logger) LoggerFactory.getLogger(type);
        appender.start();
        logger.addAppender(appender);
    }

    /** Cada evento como "NÍVEL mensagem formatada". */
    public List<String> lines() {
        return appender.list.stream()
                .map(event -> event.getLevel() + " " + event.getFormattedMessage())
                .toList();
    }

    public String all() {
        return String.join("\n", lines());
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
