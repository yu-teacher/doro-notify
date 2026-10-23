package com.doro.notify.delivery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class UnitPoliciesTest {

    private final BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(30), Duration.ofMinutes(30));

    @Test
    @DisplayName("재시도 간격은 30초부터 두 배씩 늘고 30분에서 멈춘다(난수 0.5 = 흩뿌림 없음)")
    void exponentialWithCap() {
        long[] expected = {30, 60, 120, 240, 480, 960, 1800, 1800, 1800};
        for (int attempt = 1; attempt <= expected.length; attempt++) {
            assertThat(policy.delay(attempt, 0.5).toSeconds()).as("attempt " + attempt).isEqualTo(expected[attempt - 1]);
        }
    }

    @Test
    @DisplayName("흩뿌림은 ±20% 안이다")
    void jitterWithinTwentyPercent() {
        assertThat(policy.delay(3, 0.0).toSeconds()).isEqualTo(96);
        assertThat(policy.delay(3, 0.999999).toSeconds()).isBetween(143L, 144L);
        assertThat(policy.delay(1000, 0.5)).isEqualTo(Duration.ofMinutes(30));
    }
}
