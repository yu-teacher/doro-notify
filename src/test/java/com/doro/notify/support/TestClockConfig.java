package com.doro.notify.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;

/** 운영의 시스템 시계 대신 시험용 시계를 쓴다(@Primary). 한 JVM 안의 모든 시험이 같은 시계를 공유하므로 각 시험이 시작할 때 reset() 한다. */
@TestConfiguration
public class TestClockConfig {

    public static final MutableClock CLOCK = new MutableClock();

    @Bean
    @Primary
    Clock testClock() {
        return CLOCK;
    }
}
