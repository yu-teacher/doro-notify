package com.doro.notify.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 발송 워커 설정(notify.worker.*).
 *
 * @param enabled      false 면 주기 실행을 하지 않는다(시험·점검용)
 * @param pollInterval 큐를 확인하는 간격
 * @param batchSize    한 번에 가져오는 작업 수
 * @param lease        가져간 작업을 다른 워커가 건드리지 못하는 시간(처리 도중 죽었을 때 다시 가져가기까지의 시간)
 * @param maxAttempts  한 작업의 총 시도 횟수 상한(첫 시도 포함)
 * @param backoffBase  첫 재시도까지의 대기. 재시도마다 두 배가 된다
 * @param backoffMax   재시도 간격의 상한
 */
@ConfigurationProperties(prefix = "notify.worker")
public record WorkerProperties(boolean enabled, Duration pollInterval, int batchSize, Duration lease, int maxAttempts,
                               Duration backoffBase, Duration backoffMax) {

    public BackoffPolicy backoff() {
        return new BackoffPolicy(backoffBase, backoffMax);
    }
}
