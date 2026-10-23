package com.doro.notify.delivery;

import com.doro.notify.account.UserDataPurger;
import com.doro.notify.subscription.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/** 정합성 점검(주기 작업): 보관 기간이 지난 요청·작업 삭제, 탈퇴 사용자의 잔존 구독·설정 정리, 오래 쓰지 않은 구독 정리. */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    public record Summary(int requests, int leftoverSubscriptions, int staleSubscriptions) {
    }

    private final RetentionProperties properties;
    private final DeliveryJobRepository jobs;
    private final SubscriptionRepository subscriptions;
    private final UserDataPurger purger;
    private final Clock clock;

    public RetentionJob(RetentionProperties properties, DeliveryJobRepository jobs, SubscriptionRepository subscriptions,
                        UserDataPurger purger, Clock clock) {
        this.properties = properties;
        this.jobs = jobs;
        this.subscriptions = subscriptions;
        this.purger = purger;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${notify.retention.interval:PT1H}", initialDelayString = "${notify.retention.initial-delay:PT2M}")
    void scheduledRun() {
        if (!properties.enabled()) {
            return;
        }
        try {
            run();
        } catch (RuntimeException e) {
            log.warn("Retention run failed: {}", e.getClass().getSimpleName(), e);
        }
    }

    public Summary run() {
        Instant now = clock.instant();
        int requests = jobs.deleteRequestsOlderThan(now.minus(properties.requestAge()));
        int leftovers = purger.sweepLeftovers();
        int stale = subscriptions.deleteStale(now.minus(properties.staleSubscriptionAge()));
        log.info("Retention run: requestsDeleted={}, leftoverSubscriptionsDeleted={}, staleSubscriptionsDeleted={}", requests, leftovers, stale);
        return new Summary(requests, leftovers, stale);
    }
}
