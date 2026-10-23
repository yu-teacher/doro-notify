package com.doro.notify.delivery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.doro.notify.common.db.Db.instant;
import static com.doro.notify.common.db.Db.ts;
import static com.doro.notify.common.db.Db.uuid;

/**
 * delivery_jobs 접근. 가져오기는 {@code FOR UPDATE SKIP LOCKED} 로 하고, 푸시 서비스를 부르는 동안 행 잠금을 쥐지 않도록 **임대**한다:
 * 가져오면서 next_attempt_at 을 임대 만료 시각으로 밀어 둔다. 처리 도중 프로세스가 죽으면 임대가 끝난 뒤 다시 가져간다.
 * 결과 기록은 모두 {@code status = 'PENDING'} 일 때만 적용한다(다른 곳에서 이미 버려진 작업을 되살리지 않는다).
 * 끝난 작업(SENT·FAILED·DROPPED)은 페이로드를 NULL 로 비운다.
 */
@Repository
public class DeliveryJobRepository {

    /** 큐에 넣을 작업 하나(구독 하나에 대한 알림 하나). */
    public record NewJob(UUID subscriptionId, String appId, String tag, String topic, boolean urgent, byte[] payload, Instant expiresAt) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public DeliveryJobRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    /** 같은 구독의 같은 tag 로 대기 중인 옛 작업을 버린다(새 알림이 대신한다). */
    public int supersede(Collection<UUID> subscriptionIds, String tag, Instant now) {
        if (tag == null || subscriptionIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                UPDATE delivery_jobs SET status = 'DROPPED', payload = NULL, updated_at = :now
                WHERE status = 'PENDING' AND tag = :tag AND subscription_id IN (:subs)""")
                .param("now", ts(now)).param("tag", tag).param("subs", subscriptionIds).update();
    }

    public void insertAll(UUID requestId, List<NewJob> jobs, String traceId, Instant now) {
        template.batchUpdate("""
                INSERT INTO delivery_jobs (id, request_id, subscription_id, app_id, tag, topic, urgent, payload, status, attempts,
                                           next_attempt_at, expires_at, trace_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?, ?, ?)""", jobs, 500, (ps, job) -> {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, requestId);
            ps.setObject(3, job.subscriptionId());
            ps.setString(4, job.appId());
            ps.setString(5, job.tag());
            ps.setString(6, job.topic());
            ps.setBoolean(7, job.urgent());
            ps.setBytes(8, job.payload());
            ps.setObject(9, ts(now));
            ps.setObject(10, ts(job.expiresAt()));
            ps.setString(11, traceId);
            ps.setObject(12, ts(now));
            ps.setObject(13, ts(now));
        });
    }

    /** 지금 보낼 차례인 작업을 최대 {@code limit} 개 가져온다(임대 시각까지 다른 워커가 가져가지 못한다). */
    public List<DeliveryJob> claimDue(Instant now, int limit, Duration lease) {
        return jdbc.sql("""
                WITH due AS (
                    SELECT id FROM delivery_jobs WHERE status = 'PENDING' AND next_attempt_at <= :now
                    ORDER BY next_attempt_at, id LIMIT :limit FOR UPDATE SKIP LOCKED)
                UPDATE delivery_jobs j SET next_attempt_at = :leaseUntil, updated_at = :now
                FROM due WHERE j.id = due.id
                RETURNING j.id, j.request_id, j.subscription_id, j.app_id, j.topic, j.urgent, j.payload, j.attempts, j.expires_at, j.trace_id""")
                .param("now", ts(now)).param("limit", limit).param("leaseUntil", ts(now.plus(lease)))
                .query((rs, n) -> new DeliveryJob(uuid(rs, "id"), uuid(rs, "request_id"), uuid(rs, "subscription_id"), rs.getString("app_id"),
                        rs.getString("topic"), rs.getBoolean("urgent"), rs.getBytes("payload"), rs.getInt("attempts"),
                        instant(rs, "expires_at"), rs.getString("trace_id")))
                .list();
    }

    public void markSent(UUID id, int status, Instant now) {
        finish(id, "SENT", status, now);
    }

    public void markFailed(UUID id, int status, Instant now) {
        finish(id, "FAILED", status, now);
    }

    public void markDropped(UUID id, Integer status, Instant now) {
        finish(id, "DROPPED", status, now);
    }

    private void finish(UUID id, String state, Integer status, Instant now) {
        jdbc.sql("""
                UPDATE delivery_jobs SET status = :state, payload = NULL, attempts = attempts + CASE WHEN :counted THEN 1 ELSE 0 END,
                    last_status = COALESCE(:status, last_status), updated_at = :now
                WHERE id = :id AND status = 'PENDING'""")
                .param("state", state).param("counted", status != null).param("status", status).param("now", ts(now)).param("id", id).update();
    }

    /** 일시 오류: 시도 횟수를 올리고 다음 시도 시각을 정한다. */
    public void markRetry(UUID id, int status, Instant nextAttemptAt, Instant now) {
        jdbc.sql("""
                UPDATE delivery_jobs SET attempts = attempts + 1, last_status = :status, next_attempt_at = :next, updated_at = :now
                WHERE id = :id AND status = 'PENDING'""")
                .param("status", status).param("next", ts(nextAttemptAt)).param("now", ts(now)).param("id", id).update();
    }

    /** 상태별 건수. */
    public Map<String, Long> countByStatus() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String status : List.of("PENDING", "SENT", "FAILED", "DROPPED")) {
            counts.put(status, 0L);
        }
        jdbc.sql("SELECT status, count(*) AS n FROM delivery_jobs GROUP BY status")
                .query((rs, n) -> {
                    counts.put(rs.getString("status"), rs.getLong("n"));
                    return 0;
                }).list();
        return counts;
    }

    /** 가장 오래 기다린 PENDING 작업이 만들어진 시각(없으면 null). */
    public Instant oldestPendingCreatedAt() {
        return jdbc.sql("SELECT min(created_at) AS oldest FROM delivery_jobs WHERE status = 'PENDING'")
                .query((rs, n) -> instant(rs, "oldest")).optional().orElse(null);
    }

    /** 보관 기간이 지난 요청(과 연쇄로 수신 기록·발송 작업)을 지운다. */
    public int deleteRequestsOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM notify_requests WHERE created_at < :cutoff").param("cutoff", ts(cutoff)).update();
    }
}
