package com.doro.notify.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 웹 푸시 설정(notify.push.*). VAPID 키 쌍과 연락처가 모두 있어야 켜진다. 비어 있으면 푸시만 꺼진 채 서비스는 정상 기동한다.
 *
 * @param vapidPublicKey          VAPID 공개 키(비압축 P-256, base64url, 65바이트). 브라우저가 구독할 때 쓴다
 * @param vapidPrivateKey         VAPID 개인 키(base64url, 32바이트). 이 서비스만 가진다(로그·응답에 남기지 않는다)
 * @param subject                 VAPID 연락처({@code mailto:} 또는 {@code https:})
 * @param allowedHosts            구독 주소로 허용하는 푸시 서비스 도메인(그 도메인과 하위 도메인). 서버가 이 주소로 직접 접속하므로 목록에 있는 곳만 받는다
 * @param requireHttps            https 와 기본 포트만 받는다(개발·시험에서만 끈다)
 * @param requestTimeout          푸시 서비스에 보내는 요청 하나의 제한 시간
 * @param maxSubscriptionsPerApp  (사용자, 앱) 하나가 등록할 수 있는 기기 수(넘으면 가장 오래된 것부터 지운다)
 * @param failuresBeforeRemoval   거절 응답이 이만큼 연속되면 구독을 지운다
 * @param workerThreads           발송 스레드 수(푸시 서비스 호출 전용 풀)
 */
@ConfigurationProperties(prefix = "notify.push")
public record PushProperties(
        String vapidPublicKey,
        String vapidPrivateKey,
        String subject,
        List<String> allowedHosts,
        boolean requireHttps,
        Duration requestTimeout,
        int maxSubscriptionsPerApp,
        int failuresBeforeRemoval,
        int workerThreads
) {
    public PushProperties {
        allowedHosts = allowedHosts == null ? List.of() : allowedHosts.stream().map(String::trim).filter(h -> !h.isEmpty()).map(String::toLowerCase).toList();
    }

    public boolean enabled() {
        return notBlank(vapidPublicKey) && notBlank(vapidPrivateKey) && notBlank(subject);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
