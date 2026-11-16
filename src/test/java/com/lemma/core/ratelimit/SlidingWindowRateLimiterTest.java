package com.lemma.core.ratelimit;

import com.lemma.core.LogCapture;
import com.lemma.core.TestClock;
import com.lemma.core.ratelimit.SlidingWindowRateLimiter.Decision;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Janela deslizante com relógio de teste: o tempo só anda quando o teste manda. */
class SlidingWindowRateLimiterTest {

    private static final int MAX = 10;
    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final Instant T0 = Instant.parse("2026-10-20T14:30:00Z");

    private final TestClock clock = new TestClock(T0);
    private final SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(MAX, WINDOW, clock);

    private void acquireAllowed(int times) {
        for (int i = 0; i < times; i++) {
            assertEquals(new Decision(true, 0), limiter.tryAcquire(), "requisição " + (i + 1));
        }
    }

    private void assertRefused(long retryAfterSeconds) {
        assertEquals(new Decision(false, retryAfterSeconds), limiter.tryAcquire());
    }

    // ---------- limite ----------

    @Test
    void theFirstMaxPassAndTheNextIsRefusedWithRetryAfter() {
        acquireAllowed(MAX);

        assertRefused(60);
    }

    @Test
    void retryAfterIsTheTimeUntilTheOldestLeavesTheWindow() {
        acquireAllowed(MAX);
        clock.advance(Duration.ofSeconds(10));

        assertRefused(50);
    }

    @Test
    void retryAfterIsRoundedUpAndNeverBelowOne() {
        acquireAllowed(MAX);

        clock.set(T0.plusMillis(1));            // restam 59,999 s
        assertRefused(60);
        clock.set(T0.plusMillis(59_200));       // restam 0,8 s
        assertRefused(1);
        clock.set(T0.plusSeconds(59));          // resta 1 s exato
        assertRefused(1);
        clock.set(T0.plusNanos(59_999_999_999L)); // resta 1 ns
        assertRefused(1);
        clock.set(T0.plusSeconds(58).plusNanos(999_999_999)); // resta 1,000000001 s
        assertRefused(2);
    }

    @Test
    void theRefusedRequestIsNotRecorded() {
        acquireAllowed(MAX);
        clock.advance(Duration.ofSeconds(30));
        for (int i = 0; i < 5; i++) {
            assertRefused(30);
        }

        // Se as recusadas contassem, o bloqueio iria além de T0 + 60 s.
        clock.set(T0.plus(WINDOW));
        acquireAllowed(MAX);
        assertRefused(60);
    }

    @Test
    void whenTheOldestLeavesExactlyOnePasses() {
        acquireAllowed(1);
        clock.advance(Duration.ofSeconds(1));
        acquireAllowed(MAX - 1);
        assertRefused(59);

        clock.set(T0.plus(WINDOW));             // só o instante de T0 saiu

        acquireAllowed(1);
        assertRefused(1);                        // o de T0 + 1 s sai em 1 s
    }

    @Test
    void theEdgeNowEqualsTPlusWindowReleases() {
        acquireAllowed(MAX);

        clock.set(T0.plus(WINDOW).minusNanos(1));
        assertRefused(1);
        clock.set(T0.plus(WINDOW));
        acquireAllowed(MAX);
    }

    @Test
    void theWindowSlidesItIsNotFixed() {
        // Com janela fixa de 60 s alinhada em T0, as do t=61 s cairiam numa janela nova e passariam.
        clock.set(T0.plusSeconds(59));
        acquireAllowed(MAX);

        clock.set(T0.plusSeconds(61));
        for (int i = 0; i < MAX; i++) {
            assertRefused(58);
        }
    }

    @Test
    void worksWithAMaxOfOne() {
        SlidingWindowRateLimiter one = new SlidingWindowRateLimiter(1, Duration.ofSeconds(5), clock);

        assertTrue(one.tryAcquire().allowed());
        assertEquals(new Decision(false, 5), one.tryAcquire());
        clock.advance(Duration.ofSeconds(5));
        assertTrue(one.tryAcquire().allowed());
    }

    // ---------- configuração ----------

    @Test
    void invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowRateLimiter(0, WINDOW, clock));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowRateLimiter(-1, WINDOW, clock));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowRateLimiter(MAX, Duration.ZERO, clock));
        assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowRateLimiter(MAX, Duration.ofSeconds(-1), clock));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowRateLimiter(MAX, null, clock));
        assertThrows(NullPointerException.class, () -> new SlidingWindowRateLimiter(MAX, WINDOW, null));
    }

    // ---------- concorrência ----------

    @Test
    void withManySimultaneousThreadsExactlyMaxPass() throws Exception {
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 20; round++) {
                SlidingWindowRateLimiter shared = new SlidingWindowRateLimiter(MAX, WINDOW,
                        Clock.fixed(T0, ZoneOffset.UTC));
                CountDownLatch ready = new CountDownLatch(threads);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Decision>> results = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    results.add(pool.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("a largada não veio em 10 s");
                        }
                        return shared.tryAcquire();
                    }));
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS), "as threads não ficaram prontas em 10 s");
                start.countDown();

                int allowed = 0;
                for (Future<Decision> result : results) {
                    Decision decision = result.get(10, TimeUnit.SECONDS);
                    if (decision.allowed()) {
                        allowed++;
                    } else {
                        assertEquals(60, decision.retryAfterSeconds());
                    }
                }
                assertEquals(MAX, allowed, "rodada " + round);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "as threads não terminaram em 10 s");
        }
    }

    // ---------- log ----------

    @Test
    void theLimiterDoesNotLog() {
        try (LogCapture logs = new LogCapture(SlidingWindowRateLimiter.class)) {
            acquireAllowed(MAX);
            assertRefused(60);

            assertEquals("", logs.all());
        }
    }
}
