package com.doro.notify.subscription;

import java.time.Instant;
import java.util.UUID;

/** 저장된 푸시 구독 한 건. endpoint·p256dh·auth 는 비밀이므로 {@link #toString()} 에 싣지 않는다(실수로 로그에 찍혀도 새지 않게). */
public record Subscription(UUID id, UUID userId, String appId, String deviceId, String endpoint, String p256dh, String auth,
                           String userAgent, int failCount, Instant createdAt, Instant lastSuccessAt, Instant lastSeenAt) {

    @Override
    public String toString() {
        return "Subscription[id=" + id + ", appId=" + appId + "]";
    }
}
