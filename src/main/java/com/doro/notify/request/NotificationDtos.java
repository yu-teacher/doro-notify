package com.doro.notify.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class NotificationDtos {

    private NotificationDtos() {
    }

    /** 알림 요청(설계 7.2). 제목·본문은 로그에 남기지 않으므로 toString 에서 뺀다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NotificationRequest(String idempotencyKey, String app, String type, List<UUID> recipients, String title, String body,
                                      String tag, String url, String icon, Integer ttlSeconds, String urgency, Instant expiresAt,
                                      Boolean dryRun) {
        @Override
        public String toString() {
            return "NotificationRequest[app=" + app + ", type=" + type + ", recipients=" + (recipients == null ? 0 : recipients.size()) + "]";
        }
    }

    /**
     * 즉시 응답(큐 적재 기준). 수신자 수 기준: accepted(보낼 기기가 있고 설정이 켜짐)·suppressed(앱·종류를 꺼 둠)·noSubscription(기기 없음).
     * devices 는 큐에 넣은 기기(구독) 수다(dryRun 이면 넣었을 기기 수).
     */
    public record NotificationResult(String outcome, int accepted, int suppressed, int noSubscription, int devices) {
    }

    public record TypeRegistration(String type, String label, String description, Boolean defaultOn) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TypesRequest(List<TypeRegistration> types) {
    }
}
