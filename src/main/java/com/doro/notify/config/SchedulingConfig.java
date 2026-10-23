package com.doro.notify.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 발송 워커·정리 작업·SDK 탈퇴 동기화의 주기 실행을 켠다. 각 작업은 자기 설정으로 끌 수 있다. */
@Configuration
@EnableScheduling
class SchedulingConfig {
}
