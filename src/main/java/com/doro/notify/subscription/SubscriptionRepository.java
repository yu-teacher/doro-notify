package com.doro.notify.subscription;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.doro.notify.common.db.Db.instant;
import static com.doro.notify.common.db.Db.ts;
import static com.doro.notify.common.db.Db.uuid;

/** push_subscriptions 접근. 모든 해제는 사용자 id 조건을 함께 건다(내 것만 지울 수 있다). */
@Repository
public class SubscriptionRepository {

    private static final String COLUMNS = "id, user_id, app_id, device_id, endpoint, p256dh, auth, user_agent, fail_count, created_at, last_success_at, last_seen_at";

    private static final RowMapper<Subscription> MAPPER = (rs, n) -> new Subscription(
            uuid(rs, "id"), uuid(rs, "user_id"), rs.getString("app_id"), rs.getString("device_id"), rs.getString("endpoint"),
            rs.getString("p256dh"), rs.getString("auth"), rs.getString("user_agent"), rs.getInt("fail_count"),
            instant(rs, "created_at"), instant(rs, "last_success_at"), instant(rs, "last_seen_at"));

    private final JdbcClient jdbc;

    public SubscriptionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 구독을 등록(upsert)한다. 같은 endpoint 가 이미 있으면 **소유가 이 사용자로 옮겨진다**(같은 기기에서 계정을 바꿔 로그인). 소유자나 앱이 바뀌면
     * 이전 소유자 앞으로 쌓인 발송 작업은 모두 버린다(남의 알림이 새 소유자 기기로 가지 않게). 그 뒤 (사용자, 앱)당 상한을 넘으면 가장 오래된 것부터 지운다.
     *
     * @return 상한 때문에 지운 구독 수
     */
    @Transactional
    public int upsert(UUID userId, String appId, String deviceId, String endpoint, String p256dh, String auth, String userAgent,
                      Instant now, int maxPerApp) {
        jdbc.sql("SELECT id, user_id, app_id FROM push_subscriptions WHERE endpoint = :e FOR UPDATE")
                .param("e", endpoint)
                .query((rs, n) -> new Object[] {uuid(rs, "id"), uuid(rs, "user_id"), rs.getString("app_id")})
                .optional()
                .ifPresent(existing -> {
                    if (!userId.equals(existing[1]) || !appId.equals(existing[2])) {
                        jdbc.sql("DELETE FROM delivery_jobs WHERE subscription_id = :s").param("s", existing[0]).update();
                    }
                });
        jdbc.sql("""
                INSERT INTO push_subscriptions (id, user_id, app_id, device_id, endpoint, p256dh, auth, user_agent, fail_count, created_at, last_seen_at)
                VALUES (:id, :u, :a, :d, :e, :p, :auth, :ua, 0, :now, :now)
                ON CONFLICT (endpoint) DO UPDATE SET
                    created_at = CASE WHEN push_subscriptions.user_id <> EXCLUDED.user_id OR push_subscriptions.app_id <> EXCLUDED.app_id
                                      THEN EXCLUDED.created_at ELSE push_subscriptions.created_at END,
                    user_id = EXCLUDED.user_id, app_id = EXCLUDED.app_id, device_id = EXCLUDED.device_id,
                    p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth, user_agent = EXCLUDED.user_agent,
                    fail_count = 0, last_seen_at = EXCLUDED.last_seen_at""")
                .param("id", UUID.randomUUID()).param("u", userId).param("a", appId).param("d", deviceId).param("e", endpoint)
                .param("p", p256dh).param("auth", auth).param("ua", userAgent).param("now", ts(now))
                .update();
        // 이 구독을 뺀 나머지 중 최근 (max-1)개만 남긴다 = 이 구독을 포함해 max 개
        return jdbc.sql("""
                DELETE FROM push_subscriptions WHERE id IN (
                    SELECT id FROM push_subscriptions WHERE user_id = :u AND app_id = :a AND endpoint <> :e
                    ORDER BY created_at DESC, id OFFSET :keep)""")
                .param("u", userId).param("a", appId).param("e", endpoint).param("keep", Math.max(0, maxPerApp - 1))
                .update();
    }

    public Optional<Subscription> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM push_subscriptions WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public List<Subscription> findByUser(UUID userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM push_subscriptions WHERE user_id = :u ORDER BY created_at, id")
                .param("u", userId).query(MAPPER).list();
    }

    public List<Subscription> findByUserAndApp(UUID userId, String appId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM push_subscriptions WHERE user_id = :u AND app_id = :a ORDER BY created_at, id")
                .param("u", userId).param("a", appId).query(MAPPER).list();
    }

    /** 수신자들의 해당 앱 구독을 한 번에 가져온다. */
    public List<Subscription> findByUsersAndApp(Collection<UUID> userIds, String appId) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM push_subscriptions WHERE app_id = :a AND user_id IN (:users) ORDER BY created_at, id")
                .param("a", appId).param("users", userIds).query(MAPPER).list();
    }

    /** 내 구독 하나를 지운다. 없거나 남의 것이면 0(오류가 아니다). */
    public int deleteByEndpoint(UUID userId, String endpoint) {
        return jdbc.sql("DELETE FROM push_subscriptions WHERE endpoint = :e AND user_id = :u").param("e", endpoint).param("u", userId).update();
    }

    /** 이 앱으로 등록된 내 구독만 지운다(서비스가 자기 앱의 구독만 정리할 때). */
    public int deleteByEndpointInApp(UUID userId, String appId, String endpoint) {
        return jdbc.sql("DELETE FROM push_subscriptions WHERE endpoint = :e AND user_id = :u AND app_id = :a")
                .param("e", endpoint).param("u", userId).param("a", appId).update();
    }

    /** 이 기기(deviceId)의 내 모든 앱 구독을 지운다. */
    public int deleteByDevice(UUID userId, String deviceId) {
        return jdbc.sql("DELETE FROM push_subscriptions WHERE device_id = :d AND user_id = :u").param("d", deviceId).param("u", userId).update();
    }

    /** 푸시 서비스가 구독이 없어졌다고 알렸다(404/410): 발송 작업과 함께 지운다. */
    public int deleteById(UUID id) {
        return jdbc.sql("DELETE FROM push_subscriptions WHERE id = :id").param("id", id).update();
    }

    public void recordSuccess(UUID id, Instant now) {
        jdbc.sql("UPDATE push_subscriptions SET fail_count = 0, last_success_at = :now WHERE id = :id")
                .param("id", id).param("now", ts(now)).update();
    }

    /** 거절 응답 한 번을 기록하고, 연속 {@code limit} 번째면 구독을 지운다. @return 지웠으면 true */
    @Transactional
    public boolean recordRejection(UUID id, int limit) {
        jdbc.sql("UPDATE push_subscriptions SET fail_count = fail_count + 1 WHERE id = :id").param("id", id).update();
        return jdbc.sql("DELETE FROM push_subscriptions WHERE id = :id AND fail_count >= :limit").param("id", id).param("limit", limit).update() > 0;
    }

    /** 오래 접속도 성공도 없는 구독을 지운다(주기 작업). */
    public int deleteStale(Instant cutoff) {
        return jdbc.sql("DELETE FROM push_subscriptions WHERE GREATEST(last_seen_at, COALESCE(last_success_at, last_seen_at)) < :cutoff")
                .param("cutoff", ts(cutoff)).update();
    }
}
