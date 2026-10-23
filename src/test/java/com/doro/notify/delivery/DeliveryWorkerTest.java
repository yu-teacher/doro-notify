package com.doro.notify.delivery;

import com.doro.notify.push.PushTestSupport;
import com.doro.notify.push.PushTestSupport.TestDevice;
import com.doro.notify.support.AbstractNotifyTest;
import com.doro.notify.support.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 발송 워커: 전달·재시도(백오프)·삭제 정책(404/410, 거절 5회 연속, 일시 오류로는 삭제 금지)·만료·임대·동시 워커. */
class DeliveryWorkerTest extends AbstractNotifyTest {

    @Autowired
    DeliveryWorker worker;
    @Autowired
    DeliveryJobRepository jobs;

    final UUID alice = UUID.randomUUID();
    int keySequence;

    @BeforeEach
    void types() {
        registerType("party", "comment", true);
    }

    private String notifyBody(String key, String tag, UUID user, String extra) {
        return "{\"idempotencyKey\":\"" + key + "\",\"app\":\"party\",\"type\":\"comment\",\"recipients\":[\"" + user + "\"],\"title\":\"핀 이름\","
                + "\"body\":\"작성자: 댓글 😀\",\"tag\":\"" + tag + "\",\"url\":\"/party/?map=1&pin=2\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    /** 알림 하나를 요청한다(큐에 쌓인다). */
    private void request(UUID user, String tag, String extra) throws Exception {
        perform(internal(post("/internal/v1/notifications"), PARTY_TOKEN).content(notifyBody("k" + (++keySequence), tag, user, extra)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("ACCEPTED"));
    }

    private Map<String, Object> job() {
        return jdbc.queryForMap("select status, attempts, last_status, payload, next_attempt_at, expires_at from delivery_jobs order by created_at desc limit 1");
    }

    private int subscriptions() {
        return jdbc.queryForObject("select count(*) from push_subscriptions", Integer.class);
    }

    private int failCount() {
        return jdbc.queryForObject("select fail_count from push_subscriptions", Integer.class);
    }

    private void passTime(Duration duration) {
        TestClockConfig.CLOCK.advance(duration);
    }

    // -- 전달

    @Test
    @DisplayName("전달: 푸시 서비스가 받고, 기기가 복호화한 메시지는 계약 v1 이며, 작업은 SENT 로 끝나고 페이로드는 비워진다")
    void deliversAndClearsPayload() throws Exception {
        TestDevice device = subscribe(alice, "party", "device-aaaaaaaa");
        request(alice, "pin:1", "");
        assertThat(job().get("payload")).as("큐에 있는 동안은 페이로드가 있다").isNotNull();

        assertThat(worker.runUntilIdle()).isEqualTo(1);

        assertThat(device.received()).hasSize(1);
        assertThat(com.jayway.jsonpath.JsonPath.<Integer>read(device.message(0), "$.v")).isEqualTo(1);
        assertThat(device.field(0, "app")).isEqualTo("party");
        assertThat(device.field(0, "type")).isEqualTo("comment");
        assertThat(device.field(0, "title")).isEqualTo("핀 이름");
        assertThat(device.field(0, "body")).isEqualTo("작성자: 댓글 😀");
        assertThat(device.field(0, "tag")).isEqualTo("pin:1");
        assertThat(device.field(0, "url")).isEqualTo("/party/?map=1&pin=2");
        var headers = device.received().get(0).headers();
        assertThat(headers.get("content-encoding")).isEqualTo("aes128gcm");
        assertThat(headers.get("authorization")).startsWith("vapid t=");
        assertThat(headers.get("topic")).hasSize(32);
        assertThat(Long.parseLong(headers.get("ttl"))).isBetween(86_000L, 86_400L);
        assertThat(headers.get("urgency")).isEqualTo("normal");

        Map<String, Object> done = job();
        assertThat(done.get("status")).isEqualTo("SENT");
        assertThat(done.get("attempts")).isEqualTo(1);
        assertThat(done.get("payload")).as("발송 뒤에는 알림 내용을 남기지 않는다").isNull();
        assertThat(jdbc.queryForObject("select last_success_at is not null from push_subscriptions", Boolean.class)).isTrue();
        assertThat(worker.runUntilIdle()).as("끝난 작업은 다시 가져가지 않는다").isZero();
        assertThat(device.received()).hasSize(1);
    }

    @Test
    @DisplayName("urgency=high 는 Urgency 헤더로, ttlSeconds 는 TTL 헤더로 전달된다")
    void urgencyAndTtl() throws Exception {
        TestDevice device = subscribe(alice, "party", "device-aaaaaaaa");
        request(alice, "pin:1", "\"urgency\":\"high\",\"ttlSeconds\":600");
        worker.runUntilIdle();
        assertThat(device.received().get(0).headers().get("urgency")).isEqualTo("high");
        assertThat(Long.parseLong(device.received().get(0).headers().get("ttl"))).isBetween(598L, 600L);
    }

    @Test
    @DisplayName("한 사용자의 모든 기기로 한 번씩 간다")
    void allDevicesOfTheUser() throws Exception {
        TestDevice phone = subscribe(alice, "party", "device-aaaaaaaa");
        TestDevice laptop = subscribe(alice, "party", "device-bbbbbbbb");
        request(alice, "pin:1", "");
        worker.runUntilIdle();
        assertThat(phone.received()).hasSize(1);
        assertThat(laptop.received()).hasSize(1);
    }

    // -- 일시 오류: 백오프 재시도, 구독은 절대 지우지 않는다

    @Test
    @DisplayName("일시 오류(503)는 지수 백오프로 다시 시도해 성공하고, 그동안 구독은 그대로다")
    void retriesTransientErrorsWithBackoff() throws Exception {
        TestDevice device = subscribe(alice, "party", "device-aaaaaaaa");
        PushTestSupport.SERVICE.respondWith(device.name, 503);
        request(alice, "pin:1", "");

        worker.runUntilIdle();
        Map<String, Object> first = job();
        assertThat(first.get("status")).isEqualTo("PENDING");
        assertThat(first.get("attempts")).isEqualTo(1);
        assertThat(first.get("last_status")).isEqualTo(503);
        assertThat(first.get("payload")).isNotNull();
        assertThat(device.received()).hasSize(1);

        worker.runUntilIdle();
        assertThat(device.received()).as("대기 시간이 지나기 전에는 다시 보내지 않는다").hasSize(1);

        passTime(Duration.ofSeconds(40));
        PushTestSupport.SERVICE.respondWith(device.name, 201);
        worker.runUntilIdle();

        assertThat(device.received()).hasSize(2);
        Map<String, Object> done = job();
        assertThat(done.get("status")).isEqualTo("SENT");
        assertThat(done.get("attempts")).isEqualTo(2);
        assertThat(done.get("payload")).isNull();
        assertThat(subscriptions()).isEqualTo(1);
        assertThat(failCount()).as("일시 오류는 거절 횟수에 세지 않는다").isZero();
    }

    @Test
    @DisplayName("429 도 일시 오류다: 재시도하고 구독을 지우지 않는다")
    void tooManyRequestsIsTransient() throws Exception {
        TestDevice device = subscribe(alice, "party", "device-aaaaaaaa");
        PushTestSupport.SERVICE.respondWith(device.name, 429);
        request(alice, "pin:1", "");
        worker.runUntilIdle();
        assertThat(job().get("status")).isEqualTo("PENDING");
        assertThat(subscriptions()).isEqualTo(1);
    }

    @Test
    @DisplayName("연결할 수 없어도 일시 오류로 보고 재시도하며 구독을 지우지 않는다")
    void unreachablePushServiceIsTransient() throws Exception {
        subscribe(alice, "party", "device-aaaaaaaa");
        jdbc.update("update push_subscriptions set endpoint = 'http://localhost:1/push/unreachable'");
        request(alice, "pin:1", "");
        worker.runUntilIdle();
        Map<String, Object> state = job();
        assertThat(state.get("status")).isEqualTo("PENDING");
        assertThat(state.get("last_status")).isEqualTo(0);
        assertThat(subscriptions()).isEqualTo(1);
        assertThat(failCount()).isZero();
    }

    @Test
    @DisplayName("재시도는 총 8번(첫 시도 포함)까지: 모두 실패하면 FAILED 로 남고 페이로드는 비워지며 구독은 그대로다")
    void givesUpAfterMaxAttempts() throws Exception {
        TestDevice device = subscribe(alice, "party", "device-aaaaaaaa");
        PushTestSupport.SERVICE.respondWith(device.name, 500);
        request(alice, "pin:1", "");
        for (int i = 0; i < 12; i++) {
            worker.runUntilIdle();
            passTime(Duration.ofMinutes(40));
        }
        assertThat(device.received()).hasSize(8);
        Map<String, Object> state = job();
        assertThat(state.get("status")).isEqualTo("FAILED");
        assertThat(state.get("attempts")).isEqualTo(8);
        assertThat(state.get("payload")).isNull();
        assertThat(subscriptions()).as("재시도가 소진돼도 일시 오류로는 구독을 지우지 않는다").isEqualTo(1);
    }

    @Test
    @DisplayName("재시도는 알림의 만료 시각 안에서만: 다음 시도가 만료 뒤면 바로 버린다(DROPPED)")
    void doesNotRetryPastExpiry() throws Exception {
        TestDevice device = subscribe(alice, "party", "device-aaaaaaaa");
        PushTestSupport.SERVICE.respondWith(device.name, 503);
        request(alice, "pin:1", "\"ttlSeconds\":20");
        worker.runUntilIdle();
        Map<String, Object> state = job();
        assertThat(state.get("status")).isEqualTo("DROPPED");
        assertThat(state.get("payload")).isNull();
        assertThat(subscriptions()).isEqualTo(1);
    }

    @Test
    @DisplayName("보내기 전에 만료 시각이 지난 작업은 보내지 않고 버린다(낡은 알림이 늦게 오지 않게)")
    void expiredJobsAreDroppedNotSent() throws Exception {
        TestDevice device = subscribe(alice, "party", "device-aaaaaaaa");
        request(alice, "pin:1", "\"ttlSeconds\":60");
        passTime(Duration.ofMinutes(2));
        worker.runUntilIdle();
        assertThat(device.received()).isEmpty();
        Map<String, Object> state = job();
        assertThat(state.get("status")).isEqualTo("DROPPED");
        assertThat(state.get("payload")).isNull();
    }

    // -- 삭제 정책

    @Test
    @DisplayName("404/410 은 구독이 없어진 것이므로 그 구독만 지운다(다른 기기는 그대로)")
    void goneDeletesSubscription() throws Exception {
        for (int status : new int[] {404, 410}) {
            jdbc.execute("truncate delivery_jobs, notify_request_recipients, notify_requests, push_subscriptions cascade");
            TestDevice gone = subscribe(alice, "party", "device-aaaaaaaa");
            TestDevice alive = subscribe(alice, "party", "device-bbbbbbbb");
            PushTestSupport.SERVICE.respondWith(gone.name, status);
            request(alice, "pin:" + status, "");
            worker.runUntilIdle();
            assertThat(subscriptions()).as("상태 " + status).isEqualTo(1);
            assertThat(jdbc.queryForObject("select endpoint from push_subscriptions", String.class)).isEqualTo(alive.endpoint);
            assertThat(alive.received()).hasSize(1);
        }
    }

    @Test
    @DisplayName("거절(400)이 연속 5번이면 구독을 지우고, 4번까지는 남기며, 중간에 성공하면 연속 횟수가 0 으로 돌아간다")
    void deletesAfterFiveConsecutiveRejections() throws Exception {
        TestDevice device = subscribe(alice, "party", "device-aaaaaaaa");
        PushTestSupport.SERVICE.respondWith(device.name, 400);
        for (int i = 1; i <= 4; i++) {
            request(alice, "pin:" + i, "");
            worker.runUntilIdle();
            assertThat(job().get("status")).as("거절은 재시도하지 않는다").isEqualTo("FAILED");
            assertThat(failCount()).isEqualTo(i);
        }
        PushTestSupport.SERVICE.respondWith(device.name, 201);
        request(alice, "pin:ok", "");
        worker.runUntilIdle();
        assertThat(failCount()).as("성공하면 연속 횟수가 0 으로").isZero();

        PushTestSupport.SERVICE.respondWith(device.name, 403);
        for (int i = 1; i <= 4; i++) {
            request(alice, "pin:b" + i, "");
            worker.runUntilIdle();
        }
        assertThat(subscriptions()).isEqualTo(1);
        request(alice, "pin:last", "");
        worker.runUntilIdle();
        assertThat(subscriptions()).as("연속 5번째 거절").isZero();
    }

    @Test
    @DisplayName("보낼 때 저장된 주소를 다시 검증한다: 허용 목록 밖으로 바뀐 주소로는 접속하지 않고 거절로 센다")
    void revalidatesStoredEndpoint() throws Exception {
        subscribe(alice, "party", "device-aaaaaaaa");
        jdbc.update("update push_subscriptions set endpoint = 'http://localhost.evil.example/push/x'");
        request(alice, "pin:1", "");
        worker.runUntilIdle();
        assertThat(job().get("status")).isEqualTo("FAILED");
        assertThat(job().get("last_status")).isEqualTo(-1);
        assertThat(failCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("깨진 키가 저장돼 있어도 그 기기만 거절로 세고 다른 기기 발송은 계속된다")
    void brokenStoredKeysDoNotStopOthers() throws Exception {
        subscribe(alice, "party", "device-aaaaaaaa");
        TestDevice good = subscribe(alice, "party", "device-bbbbbbbb");
        jdbc.update("update push_subscriptions set p256dh = 'AAAA' where endpoint <> ?", good.endpoint);
        request(alice, "pin:1", "");
        worker.runUntilIdle();
        assertThat(good.received()).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from delivery_jobs where status = 'FAILED'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("사용자가 기기를 해제하면 대기 중인 작업은 함께 사라져 보내지지 않는다")
    void unsubscribeDropsPendingJobs() throws Exception {
        TestDevice device = subscribe(alice, "party", "device-aaaaaaaa");
        request(alice, "pin:1", "");
        perform(hub(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/notify/devices/device-aaaaaaaa"), alice)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from delivery_jobs", Integer.class)).isZero();
        worker.runUntilIdle();
        assertThat(device.received()).isEmpty();
    }

    // -- 큐 동작

    @Test
    @DisplayName("가져간 작업은 임대 시간 동안 다시 가져가지 않고, 임대가 끝나면(처리 도중 죽은 경우) 다시 가져간다")
    void leaseProtectsInFlightJobs() throws Exception {
        subscribe(alice, "party", "device-aaaaaaaa");
        request(alice, "pin:1", "");
        var now = TestClockConfig.CLOCK.instant();
        assertThat(jobs.claimDue(now, 10, Duration.ofMinutes(2))).hasSize(1);
        assertThat(jobs.claimDue(now, 10, Duration.ofMinutes(2))).isEmpty();
        assertThat(jobs.claimDue(now.plus(Duration.ofMinutes(1)), 10, Duration.ofMinutes(2))).isEmpty();
        assertThat(jobs.claimDue(now.plus(Duration.ofMinutes(3)), 10, Duration.ofMinutes(2))).hasSize(1);
    }

    @Test
    @DisplayName("워커 둘이 동시에 돌아도(SKIP LOCKED) 모든 알림이 정확히 한 번씩만 전달된다")
    void concurrentWorkersDeliverExactlyOnce() throws Exception {
        List<TestDevice> devices = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            devices.add(subscribe(UUID.randomUUID(), "party", "device-" + String.format("%08d", i)));
        }
        // 사용자마다 알림 하나: 40명에게 한 번에 요청
        StringBuilder recipients = new StringBuilder();
        for (UUID user : jdbc.queryForList("select user_id from push_subscriptions", UUID.class)) {
            recipients.append(recipients.isEmpty() ? "" : ",").append('"').append(user).append('"');
        }
        perform(internal(post("/internal/v1/notifications"), PARTY_TOKEN).content("{\"idempotencyKey\":\"bulk\",\"app\":\"party\",\"type\":\"comment\",\"recipients\":["
                + recipients + "],\"title\":\"t\",\"body\":\"b\",\"url\":\"/party/\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.devices").value(40));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(worker::runUntilIdle);
            Future<Integer> b = pool.submit(worker::runUntilIdle);
            assertThat(a.get() + b.get()).isEqualTo(40);
        } finally {
            pool.shutdownNow();
        }
        for (TestDevice device : devices) {
            assertThat(device.received()).hasSize(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from delivery_jobs where status = 'SENT'", Integer.class)).isEqualTo(40);
    }

    @Test
    @DisplayName("같은 구독에 같은 tag 가 연달아 오면 대기 중인 옛 알림은 버려지고 마지막 것만 전달된다")
    void sameTagIsCollapsedInQueue() throws Exception {
        TestDevice device = subscribe(alice, "party", "device-aaaaaaaa");
        request(alice, "pin:1", "");
        request(alice, "pin:1", "");
        request(alice, "pin:2", "");
        worker.runUntilIdle();
        assertThat(device.received()).hasSize(2);
        assertThat(jdbc.queryForObject("select count(*) from delivery_jobs where status = 'DROPPED'", Integer.class)).isEqualTo(1);
    }

    // -- 시험 알림

    @Test
    @DisplayName("시험 알림: 허브 경로는 내 모든 기기로, 앱 경로는 그 앱의 기기로만 즉시 보내고 결과를 준다")
    void testNotification() throws Exception {
        TestDevice party = subscribe(alice, "party", "device-aaaaaaaa");
        TestDevice blog = subscribe(alice, "blog", "device-aaaaaaaa");
        TestDevice other = subscribe(UUID.randomUUID(), "party", "device-cccccccc");

        perform(app(post("/api/v1/notify/test"), alice, "party")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices").value(1)).andExpect(jsonPath("$.data.delivered").value(1));
        assertThat(party.received()).hasSize(1);
        assertThat(blog.received()).isEmpty();
        assertThat(party.field(0, "app")).isEqualTo("party");
        assertThat(party.field(0, "type")).isEqualTo("test");
        assertThat(party.field(0, "url")).isEqualTo("/party/");

        perform(hub(post("/api/v1/notify/test"), alice)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices").value(2)).andExpect(jsonPath("$.data.delivered").value(2));
        assertThat(blog.received()).hasSize(1);
        assertThat(blog.field(0, "url")).isEqualTo("/blog/");
        assertThat(other.received()).as("남의 기기로는 가지 않는다").isEmpty();
    }

    @Test
    @DisplayName("시험 알림은 요청 본문이 필요 없고, 기기가 없으면 0 건으로 성공한다")
    void testNotificationHasNoBody() throws Exception {
        perform(hub(post("/api/v1/notify/test").contentType(MediaType.APPLICATION_JSON), alice)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices").value(0));
    }
}
