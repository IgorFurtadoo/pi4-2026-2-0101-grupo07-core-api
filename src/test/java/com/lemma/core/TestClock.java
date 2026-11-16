package com.lemma.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Relógio de teste que só anda quando o teste manda (advance ou set), sem
 * Thread.sleep. Pode ser lido por várias threads ao mesmo tempo (volatile).
 */
public final class TestClock extends Clock {

    private volatile Instant now;

    public TestClock(Instant start) {
        this.now = start;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    public void set(Instant instant) {
        now = instant;
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /** Sempre UTC: os testes não precisam de outra zona. */
    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
