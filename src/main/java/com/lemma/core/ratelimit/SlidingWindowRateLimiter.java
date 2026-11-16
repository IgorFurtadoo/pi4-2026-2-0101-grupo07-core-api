package com.lemma.core.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Limite de requisições com janela deslizante e registro de instantes (seção 8.6 do
 * desenho). Deixa passar no máximo {@code max} requisições em qualquer intervalo de
 * duração {@code window}.
 *
 * Guarda os instantes das requisições aceitas, no máximo {@code max}. A cada
 * requisição: descarta os instantes que já saíram da janela (um instante t sai
 * quando agora >= t + janela); se ainda couber, registra agora e deixa passar; se
 * não couber, recusa sem registrar (a recusada não prolonga o bloqueio) e informa
 * em quantos segundos o instante mais antigo sai da janela.
 *
 * Escolhida em vez da janela fixa porque esta deixa passar o dobro do limite na
 * virada de uma janela para a outra.
 *
 * Em memória: só vale com uma instância do serviço, e reiniciar zera. O relógio é
 * injetável para os testes. Sem log: uma recusa não vai ao log.
 */
public class SlidingWindowRateLimiter {

    /**
     * Resultado de {@link #tryAcquire()}. Quando recusada, retryAfterSeconds diz em
     * quantos segundos inteiros (arredondado para cima, no mínimo 1) cabe uma nova
     * requisição; quando aceita, é 0.
     */
    public record Decision(boolean allowed, long retryAfterSeconds) {

        static final Decision ALLOWED = new Decision(true, 0);

        static Decision refused(long retryAfterSeconds) {
            return new Decision(false, retryAfterSeconds);
        }
    }

    private final int max;
    private final Duration window;
    private final Clock clock;
    /** Instantes das requisições aceitas ainda dentro da janela, do mais antigo ao mais novo. */
    private final Deque<Instant> accepted = new ArrayDeque<>();

    public SlidingWindowRateLimiter(int max, Duration window, Clock clock) {
        if (max < 1) {
            throw new IllegalArgumentException("max deve ser ao menos 1");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window deve ser positiva");
        }
        this.max = max;
        this.window = window;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Decide e registra de uma vez: a verificação e o registro ficam sob o mesmo
     * synchronized, então duas requisições simultâneas não ocupam a mesma vaga.
     */
    public synchronized Decision tryAcquire() {
        Instant now = clock.instant();
        while (!accepted.isEmpty() && !now.isBefore(accepted.peekFirst().plus(window))) {
            accepted.removeFirst();
        }
        if (accepted.size() < max) {
            accepted.addLast(now);
            return Decision.ALLOWED;
        }
        return Decision.refused(secondsUntilOldestLeaves(now));
    }

    /**
     * Segundos até o instante mais antigo sair da janela, arredondados para cima e
     * no mínimo 1. Conta com segundos e nanossegundos inteiros do Duration, sem
     * double: truncar para milissegundos poderia arredondar para baixo.
     */
    private long secondsUntilOldestLeaves(Instant now) {
        Duration remaining = Duration.between(now, accepted.peekFirst().plus(window));
        long seconds = remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }
}
