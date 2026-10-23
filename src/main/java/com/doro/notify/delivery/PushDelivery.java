package com.doro.notify.delivery;

import com.doro.notify.push.PushEndpointPolicy;
import com.doro.notify.push.PushProperties;
import com.doro.notify.push.WebPushClient;
import com.doro.notify.push.WebPushEncryptor;
import com.doro.notify.subscription.Subscription;
import com.doro.notify.subscription.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/**
 * 구독 하나로 메시지 하나를 실제로 보내고, 결과를 구독에 반영한다.
 * <ul>
 *   <li>전달됨: 마지막 성공 시각을 갱신하고 연속 거절 횟수를 0 으로 되돌린다.</li>
 *   <li>404/410: 구독이 없어진 것이므로 지운다.</li>
 *   <li>거절(키 불일치·형식 오류 등): 연속 거절 횟수를 올리고 {@code failures-before-removal} 번째면 지운다.</li>
 *   <li>일시 오류(시간 초과·429·5xx·연결 실패): 구독은 **절대 지우지 않는다**.</li>
 * </ul>
 * 저장된 주소는 보낼 때 허용 목록으로 다시 확인한다. 로그에는 구독 id·푸시 서비스 호스트·상태 코드만 남긴다.
 */
@Component
public class PushDelivery {

    private static final Logger log = LoggerFactory.getLogger(PushDelivery.class);

    /** @param status 푸시 서비스의 HTTP 상태. 0 = 연결 실패·시간 초과, -1 = 보내기 전에 이 서버 쪽에서 거절(주소 정책·깨진 키) */
    public record Attempt(WebPushClient.Outcome outcome, int status) {
    }

    private final PushProperties properties;
    private final SubscriptionRepository subscriptions;
    private final WebPushClient client;
    private final WebPushEncryptor encryptor;
    private final PushEndpointPolicy endpointPolicy;
    private final Clock clock;

    public PushDelivery(PushProperties properties, SubscriptionRepository subscriptions, WebPushClient client,
                        WebPushEncryptor encryptor, PushEndpointPolicy endpointPolicy, Clock clock) {
        this.properties = properties;
        this.subscriptions = subscriptions;
        this.client = client;
        this.encryptor = encryptor;
        this.endpointPolicy = endpointPolicy;
        this.clock = clock;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public Attempt attempt(Subscription subscription, byte[] payload, String topic, Duration ttl, boolean urgent) {
        WebPushClient.Result result;
        try {
            URI endpoint = endpointPolicy.validate(subscription.endpoint());
            byte[] body = encryptor.encrypt(Base64.getUrlDecoder().decode(subscription.p256dh()),
                    Base64.getUrlDecoder().decode(subscription.auth()), payload);
            result = client.send(endpoint, body, topic, ttl, urgent);
        } catch (RuntimeException e) {
            // 저장된 키가 깨져 있거나 주소가 정책에 맞지 않는다: 같은 요청을 다시 보내도 소용없으므로 거절로 센다
            log.warn("Push skipped for a device: subscriptionId={}, reason={}", subscription.id(), e.getClass().getSimpleName());
            applyRejection(subscription);
            return new Attempt(WebPushClient.Outcome.REJECTED, -1);
        }
        switch (result.outcome()) {
            case DELIVERED -> subscriptions.recordSuccess(subscription.id(), clock.instant());
            case GONE -> {
                subscriptions.deleteById(subscription.id());
                log.info("Push subscription removed (gone): subscriptionId={}", subscription.id());
            }
            case REJECTED -> applyRejection(subscription);
            case RETRYABLE -> {
                // 푸시 서비스의 일시적 문제: 구독은 그대로 둔다
            }
        }
        return new Attempt(result.outcome(), result.status());
    }

    private void applyRejection(Subscription subscription) {
        if (subscriptions.recordRejection(subscription.id(), properties.failuresBeforeRemoval())) {
            log.warn("Push subscription removed after repeated rejections: subscriptionId={}", subscription.id());
        }
    }
}
