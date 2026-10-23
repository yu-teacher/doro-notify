package com.doro.notify.delivery;

import java.time.Instant;
import java.util.UUID;

/** 발송 큐에서 가져온 작업 하나. 페이로드(알림 내용)는 {@link #toString()} 에 싣지 않는다. */
public record DeliveryJob(UUID id, UUID requestId, UUID subscriptionId, String appId, String topic, boolean urgent, byte[] payload,
                          int attempts, Instant expiresAt, String traceId) {

    @Override
    public String toString() {
        return "DeliveryJob[id=" + id + ", attempts=" + attempts + "]";
    }
}
