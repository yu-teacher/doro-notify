package com.doro.notify.account;

import com.doro.notify.push.PushTestSupport.TestDevice;
import com.doro.notify.support.AbstractNotifyTest;
import com.doro.notify.support.FakeIamInternal;
import com.hunnit_beasts.doro.sdk.sync.DeletedUserHandler;
import com.hunnit_beasts.doro.sdk.sync.DeletedUsersClient;
import com.hunnit_beasts.doro.sdk.sync.DeletedUsersProperties;
import com.hunnit_beasts.doro.sdk.sync.DeletedUsersSync;
import com.hunnit_beasts.doro.sdk.sync.SyncCursorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 탈퇴 동기화: SDK 가 가짜 IAM 내부 API 를 호출해 우리 핸들러까지 닿고, 그 사용자의 구독·설정·수신 기록이 지워진다(멱등, 남의 데이터는 그대로). */
class DeletedAccountSyncTest extends AbstractNotifyTest {

    @Autowired
    DeletedUsersSync sync;
    @Autowired
    DeletedUserHandler handler;
    @Autowired
    SyncCursorStore cursors;
    @Autowired
    DeletedUsersProperties props;
    @Autowired
    UserDataPurger purger;

    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();
    final UUID carol = UUID.randomUUID(); // 방문한 적 없는 사용자

    @BeforeEach
    void seed() throws Exception {
        registerType("party", "comment", true);
        for (UUID user : new UUID[] {alice, bob}) {
            subscribe(user, "party", "device-aaaaaaaa");
            jdbc.update("insert into notify_preferences (user_id, app_id, type_id, enabled) values (?, 'party', 'comment', false)", user);
            UUID request = UUID.randomUUID();
            jdbc.update("insert into notify_requests (id, caller, idempotency_key, app_id, type_id, created_at) values (?, 'party', ?, 'party', 'comment', now())", request, request.toString());
            jdbc.update("insert into notify_request_recipients (request_id, user_id, outcome) values (?, ?, 'ACCEPTED')", request, user);
        }
    }

    private int count(String table, UUID user) {
        return jdbc.queryForObject("select count(*) from " + table + " where user_id = ?", Integer.class, user);
    }

    private boolean tombstoned(UUID user) {
        return count("notify_deleted_users", user) == 1;
    }

    @Test
    @DisplayName("핸들러는 구독·설정·수신 기록을 지우고 표식을 남기며, 두 번째 호출은 false 다(멱등)")
    void handlerPurges() {
        assertThat(handler.onDeleted(alice, Instant.now())).isTrue();
        assertThat(count("push_subscriptions", alice)).isZero();
        assertThat(count("notify_preferences", alice)).isZero();
        assertThat(count("notify_request_recipients", alice)).isZero();
        assertThat(tombstoned(alice)).isTrue();
        assertThat(handler.onDeleted(alice, Instant.now())).isFalse();
    }

    @Test
    @DisplayName("대기 중이던 발송 작업도 구독과 함께 사라지고, 다른 사용자의 데이터는 그대로다")
    void pendingJobsGoAndOthersStay() throws Exception {
        UUID request = jdbc.queryForObject("select id from notify_requests limit 1", UUID.class);
        jdbc.update("""
                insert into delivery_jobs (id, request_id, subscription_id, app_id, topic, payload, status, next_attempt_at, expires_at, created_at, updated_at)
                select gen_random_uuid(), ?, id, 'party', 't', 'x'::bytea, 'PENDING', now(), now() + interval '1 day', now(), now() from push_subscriptions""", request);
        assertThat(jdbc.queryForObject("select count(*) from delivery_jobs", Integer.class)).isEqualTo(2);

        handler.onDeleted(alice, Instant.now());

        assertThat(jdbc.queryForObject("select count(*) from delivery_jobs", Integer.class)).isEqualTo(1);
        assertThat(count("push_subscriptions", bob)).isEqualTo(1);
        assertThat(count("notify_preferences", bob)).isEqualTo(1);
        assertThat(count("notify_request_recipients", bob)).isEqualTo(1);
        assertThat(tombstoned(bob)).isFalse();
    }

    @Test
    @DisplayName("SDK 동기화가 IAM 내부 API 를 호출해 핸들러까지 가고, 같은 목록을 다시 받아도 새로 정리한 수는 0 이다")
    void sdkSyncReachesHandler() {
        IAM.delete(alice, Instant.parse("2026-10-20T00:00:00Z"));
        IAM.delete(carol, Instant.parse("2026-10-20T00:01:00Z"));

        assertThat(sync.sync()).isEqualTo(2);
        assertThat(count("push_subscriptions", alice)).isZero();
        assertThat(tombstoned(alice)).isTrue();
        assertThat(tombstoned(carol)).isTrue();
        assertThat(count("push_subscriptions", bob)).isEqualTo(1);
        assertThat(IAM.requestedSince()).isNotEmpty();

        assertThat(sync.sync()).isZero();
    }

    @Test
    @DisplayName("진행 위치는 DB 에 저장되고 앞으로만 움직인다")
    void cursorMovesForwardOnly() {
        IAM.delete(alice, Instant.parse("2026-10-20T00:00:00Z"));
        sync.sync();
        Instant stored = cursors.get(props.getName()).orElseThrow();
        assertThat(stored).isBeforeOrEqualTo(Instant.parse("2026-10-20T00:00:00Z"));

        cursors.advance(props.getName(), stored.minusSeconds(3600));
        assertThat(cursors.get(props.getName())).contains(stored);
        cursors.advance(props.getName(), stored.plusSeconds(60));
        assertThat(cursors.get(props.getName())).contains(stored.plusSeconds(60));
        assertThat(jdbc.queryForObject("select count(*) from iam_sync_state", Integer.class)).isEqualTo(1);
        assertThat(cursors.get("nobody")).isEmpty();
    }

    @Test
    @DisplayName("서비스 토큰이 틀리면 호출이 거절되어 예외로 드러나고 아무것도 지우지 않는다")
    void wrongServiceTokenSurfaces() {
        var noToken = new DeletedUsersSync(new DeletedUsersClient(IAM.url(), "", props), handler, cursors, props, Clock.systemUTC());
        IAM.delete(alice, Instant.parse("2026-10-20T00:00:00Z"));
        assertThatThrownBy(noToken::sync).hasMessageContaining("401");
        assertThat(count("push_subscriptions", alice)).isEqualTo(1);
        assertThat(tombstoned(alice)).isFalse();
    }

    @Test
    @DisplayName("탈퇴한 사용자의 아직 유효한 토큰은 구독·설정을 다시 만들지 못한다(401), 방문한 적 없는 사용자도 같다")
    void deletedUsersCannotComeBack() throws Exception {
        handler.onDeleted(alice, Instant.now());
        handler.onDeleted(carol, Instant.now());

        for (UUID user : new UUID[] {alice, carol}) {
            perform(app(putSubscription(new TestDevice(), "device-zzzzzzzz"), user, "party")).andExpect(status().isUnauthorized());
            perform(hub(get("/api/v1/notify/devices"), user)).andExpect(status().isUnauthorized());
            perform(hub(put("/api/v1/notify/preferences").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"changes\":[{\"app\":\"party\",\"type\":\"comment\",\"enabled\":true}]}"), user)).andExpect(status().isUnauthorized());
            perform(hub(post("/api/v1/notify/test"), user)).andExpect(status().isUnauthorized());
            assertThat(count("push_subscriptions", user)).isZero();
            assertThat(count("notify_preferences", user)).isZero();
        }
    }

    @Test
    @DisplayName("동기화 사이에 끼어든 잔존 구독·설정은 주기 작업이 쓸어 낸다")
    void sweepRemovesLeftovers() {
        purger.purge(carol);
        jdbc.update("insert into push_subscriptions (id, user_id, app_id, device_id, endpoint, p256dh, auth, created_at, last_seen_at) values (?, ?, 'party', 'device-leftover', 'http://localhost:1/x', 'k', 'a', now(), now())",
                UUID.randomUUID(), carol);
        jdbc.update("insert into notify_preferences (user_id, app_id, type_id, enabled) values (?, 'party', 'comment', false)", carol);

        assertThat(purger.sweepLeftovers()).isEqualTo(1);

        assertThat(count("push_subscriptions", carol)).isZero();
        assertThat(count("notify_preferences", carol)).isZero();
        assertThat(count("push_subscriptions", bob)).isEqualTo(1);
    }

    @Test
    @DisplayName("IAM 주소·토큰 설정은 SDK 가 읽고, 이 서비스에는 사용자 사본 테이블이 없다")
    void noUserCopyTable() {
        assertThat(FakeIamInternal.TOKEN).isNotBlank();
        assertThat(jdbc.queryForList("select table_name from information_schema.tables where table_schema = current_schema() order by 1", String.class))
                .doesNotContain("notify_users", "users", "notify_user")
                .contains("notify_deleted_users");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'notify_deleted_users' and table_schema = current_schema()", String.class))
                .containsExactlyInAnyOrder("user_id", "deleted_at");
    }
}
