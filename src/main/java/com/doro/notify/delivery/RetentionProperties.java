package com.doro.notify.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 정리 작업 설정(notify.retention.*).
 *
 * @param enabled               false 면 주기 실행을 하지 않는다(시험·점검용)
 * @param requestAge            이 기간이 지난 요청 기록(과 수신 기록·발송 작업)을 지운다(설계: 14일)
 * @param staleSubscriptionAge  접속도 성공도 이 기간 없던 구독을 지운다(앱을 열 때마다 구독을 다시 등록하므로 쓰지 않는 기기만 걸린다)
 * @param interval              실행 간격
 */
@ConfigurationProperties(prefix = "notify.retention")
public record RetentionProperties(boolean enabled, Duration requestAge, Duration staleSubscriptionAge, Duration interval) {
}
