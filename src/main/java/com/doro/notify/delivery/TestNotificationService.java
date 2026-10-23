package com.doro.notify.delivery;

import com.doro.notify.common.exception.ErrorCode;
import com.doro.notify.common.exception.NotifyException;
import com.doro.notify.push.WebPushClient;
import com.doro.notify.registry.AppRegistry;
import com.doro.notify.registry.NotifyApp;
import com.doro.notify.subscription.Subscription;
import com.doro.notify.subscription.SubscriptionDtos.TestResult;
import com.doro.notify.subscription.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 내 기기로 시험 알림을 바로 보낸다(큐를 거치지 않고, 알림 설정과 상관없다). 설정이 잘 됐는지 확인하는 용도라서 결과를 즉시 돌려준다.
 * 앱 경로에서 부르면 그 앱의 구독만, 허브 경로에서 부르면 내 모든 구독으로 보낸다.
 */
@Service
public class TestNotificationService {

    private static final Logger log = LoggerFactory.getLogger(TestNotificationService.class);
    private static final Duration TTL = Duration.ofMinutes(10);

    private final PushDelivery delivery;
    private final SubscriptionRepository subscriptions;
    private final AppRegistry registry;

    public TestNotificationService(PushDelivery delivery, SubscriptionRepository subscriptions, AppRegistry registry) {
        this.delivery = delivery;
        this.subscriptions = subscriptions;
        this.registry = registry;
    }

    public TestResult send(UUID userId, NotifyApp onlyApp) {
        if (!delivery.enabled()) {
            throw new NotifyException(ErrorCode.PUSH_DISABLED);
        }
        List<Subscription> targets = onlyApp == null ? subscriptions.findByUser(userId) : subscriptions.findByUserAndApp(userId, onlyApp.appId());
        int delivered = 0;
        for (Subscription subscription : targets) {
            NotifyApp app = registry.findApp(subscription.appId()).orElse(null);
            if (app == null) {
                continue;
            }
            byte[] payload = PayloadV1.build(app.appId(), "test", "알림이 켜졌어요", "이 기기로 " + app.displayName() + " 알림이 와요",
                    "test:" + app.appId(), app.scopePath(), null);
            PushDelivery.Attempt attempt = delivery.attempt(subscription, payload, "test:" + app.appId(), TTL, false);
            if (attempt.outcome() == WebPushClient.Outcome.DELIVERED) {
                delivered++;
            }
        }
        log.info("Push test sent: userId={}, devices={}, delivered={}", userId, targets.size(), delivered);
        return new TestResult(targets.size(), delivered);
    }
}
