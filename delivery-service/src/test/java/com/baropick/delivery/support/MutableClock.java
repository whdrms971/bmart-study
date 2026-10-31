package com.baropick.delivery.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** 테스트용 조정 가능한 시계 (실제 대기 없이 만료·지연 검증). */
public class MutableClock extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));

    public void advance(Duration d) { now.updateAndGet(i -> i.plus(d)); }

    public void set(Instant i) { now.set(i.truncatedTo(java.time.temporal.ChronoUnit.MICROS)); }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now.get(); }
}
