package com.doro.notify.delivery;

import java.time.Duration;

/** 지수 백오프: n 번째 재시도 전 대기 = base × 2^(n-1), max 상한, ±20% 흩뿌림(같은 순간에 몰린 작업이 같이 재시도하지 않게). */
public record BackoffPolicy(Duration base, Duration max) {

    /**
     * @param attemptsMade 지금까지 한 시도 수(1 이상)
     * @param random01     0 이상 1 미만의 난수(시험에서 고정할 수 있게 밖에서 받는다)
     */
    public Duration delay(int attemptsMade, double random01) {
        int shift = Math.min(Math.max(attemptsMade - 1, 0), 30);
        long millis = base.toMillis() * (1L << shift);
        long capped = Math.min(millis, max.toMillis());
        double jitter = 0.8 + 0.4 * random01;
        return Duration.ofMillis(Math.max(1, Math.round(capped * jitter)));
    }
}
