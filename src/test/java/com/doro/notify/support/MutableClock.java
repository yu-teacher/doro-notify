package com.doro.notify.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** 시험이 직접 움직이는 시계: 재시도 대기·만료·보관 기간을 실제로 기다리지 않고 시험한다. */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now = new AtomicReference<>(microsNow());

    public void reset() {
        now.set(microsNow());
    }

    public void advance(Duration duration) {
        now.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    /** Postgres 는 시각을 마이크로초로 저장(반올림)하므로, 나노초 시계(Linux)에서 저장값이 시험 시계와 어긋나 경계 비교가 흔들리지 않게 마이크로초로 자른다. */
    private static Instant microsNow() {
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
}
