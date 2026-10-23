package com.doro.notify.subscription;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/** 구독·기기 API 의 요청·응답 모양. 응답에는 endpoint·키를 절대 싣지 않는다. */
public final class SubscriptionDtos {

    private SubscriptionDtos() {
    }

    public record Keys(String p256dh, String auth) {
        @Override
        public String toString() {
            return "Keys[redacted]";
        }
    }

    /**
     * 브라우저의 {@code PushSubscription.toJSON()} 모양에 기기 표식을 더한 것. {@code app} 은 받아도 **신뢰하지 않는다**:
     * 앱은 게이트웨이가 붙이는 헤더로만 정해지고, 본문의 app 이 헤더와 다르면 거절한다(다른 앱 행세 방지).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubscribeRequest(String app, String deviceId, String endpoint, Keys keys) {
        @Override
        public String toString() {
            return "SubscribeRequest[redacted]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UnsubscribeRequest(String endpoint) {
        @Override
        public String toString() {
            return "UnsubscribeRequest[redacted]";
        }
    }

    /** enabled 가 false 면 서버에 VAPID 가 설정되지 않아 푸시를 켤 수 없다. */
    public record ConfigResponse(boolean enabled, String publicKey) {
    }

    public record SubscribeResponse(String app, String deviceId) {
    }

    public record RemovedResponse(int removed) {
    }

    public record DeviceApp(String app, String displayName, Instant subscribedAt, Instant lastSuccessAt) {
    }

    public record Device(String deviceId, String browser, Instant lastSeenAt, Instant lastSuccessAt, List<DeviceApp> apps) {
    }

    public record DevicesResponse(List<Device> devices) {
    }

    /** 시험 알림을 보낸 결과: 대상 기기(구독) 수와 푸시 서비스가 받아들인 수. */
    public record TestResult(int devices, int delivered) {
    }
}
