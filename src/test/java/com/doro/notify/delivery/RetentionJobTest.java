package com.doro.notify.delivery;

import com.doro.notify.support.AbstractNotifyTest;
import com.doro.notify.support.TestClockConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 정리 주기 작업: 보관 기간(14일)이 지난 요청·수신 기록·발송 작업 삭제, 오래 쓰지 않은 구독 정리. */
class RetentionJobTest extends AbstractNotifyTest {

    @Autowired
    RetentionJob retention;

    private UUID request(String key, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into notify_requests (id, caller, idempotency_key, app_id, type_id, created_at) values (?, 'party', ?, 'party', 'comment', ?)",
                id, key, java.sql.Timestamp.from(createdAt));
        jdbc.update("insert into notify_request_recipients (request_id, user_id, outcome) values (?, ?, 'NO_SUBSCRIPTION')", id, UUID.randomUUID());
        return id;
    }

    @Test
    @DisplayName("14일이 지난 요청은 수신 기록과 함께 지우고, 최근 요청과 그 작업은 남긴다")
    void deletesOldRequestsOnly() throws Exception {
        UUID user = UUID.randomUUID();
        subscribe(user, "party", "device-aaaaaaaa");
        Instant now = TestClockConfig.CLOCK.instant();
        UUID old = request("old", now.minus(Duration.ofDays(15)));
        UUID fresh = request("fresh", now.minus(Duration.ofDays(13)));
        UUID subscription = jdbc.queryForObject("select id from push_subscriptions", UUID.class);
        for (UUID request : new UUID[] {old, fresh}) {
            jdbc.update("""
                    insert into delivery_jobs (id, request_id, subscription_id, app_id, topic, status, next_attempt_at, expires_at, created_at, updated_at)
                    values (?, ?, ?, 'party', 't', 'SENT', now(), now(), now(), now())""", UUID.randomUUID(), request, subscription);
        }

        RetentionJob.Summary summary = retention.run();

        assertThat(summary.requests()).isEqualTo(1);
        assertThat(jdbc.queryForList("select idempotency_key from notify_requests", String.class)).containsExactly("fresh");
        assertThat(jdbc.queryForObject("select count(*) from notify_request_recipients", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from delivery_jobs", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from push_subscriptions", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("접속도 성공도 180일 넘게 없던 구독만 정리한다")
    void deletesStaleSubscriptions() throws Exception {
        UUID user = UUID.randomUUID();
        subscribe(user, "party", "device-aaaaaaaa");
        subscribe(user, "party", "device-bbbbbbbb");
        jdbc.update("update push_subscriptions set last_seen_at = ?, created_at = ? where device_id = 'device-aaaaaaaa'",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(200))), java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(200))));

        assertThat(retention.run().staleSubscriptions()).isEqualTo(1);
        assertThat(jdbc.queryForList("select device_id from push_subscriptions", String.class)).containsExactly("device-bbbbbbbb");

        // 오래전에 접속했어도 최근에 전달에 성공했으면 남긴다
        jdbc.update("update push_subscriptions set last_seen_at = ?, last_success_at = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(200))), java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(5))));
        assertThat(retention.run().staleSubscriptions()).isZero();
    }
}
