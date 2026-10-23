package com.doro.notify.api;

import com.doro.notify.push.PushTestSupport;
import com.doro.notify.push.PushTestSupport.TestDevice;
import com.doro.notify.support.AbstractNotifyTest;
import com.doro.notify.support.FakeDoro;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 클라이언트 API 중 구독·기기: 등록(upsert)·소유 이전·내 것만 해제·기기 단위 해제·상한·앱 식별 헤더. */
class SubscriptionApiTest extends AbstractNotifyTest {

    private static final String DEVICE_A = "device-aaaaaaaa";
    private static final String DEVICE_B = "device-bbbbbbbb";

    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();

    private int rows(String where, Object... args) {
        return jdbc.queryForObject("select count(*) from push_subscriptions where " + where, Integer.class, args);
    }

    @Test
    @DisplayName("앱 경로로 구독을 등록하면 헤더의 앱이 저장되고, 응답에 endpoint 와 키는 없다")
    void registersWithAppFromHeader() throws Exception {
        TestDevice device = new TestDevice();
        perform(app(putSubscription(device, DEVICE_A), alice, "party"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.app").value("party"))
                .andExpect(jsonPath("$.data.deviceId").value(DEVICE_A))
                .andExpect(content().string(not(containsString(device.endpoint))));
        assertThat(rows("user_id = ? and app_id = 'party' and device_id = ?", alice, DEVICE_A)).isEqualTo(1);
    }

    @Test
    @DisplayName("허브 경로(앱 헤더 없음)로는 구독을 등록할 수 없다: 403")
    void hubCannotRegister() throws Exception {
        perform(hub(putSubscription(new TestDevice(), DEVICE_A), alice))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOTIFY-403-01"));
        assertThat(rows("true")).isZero();
    }

    @Test
    @DisplayName("등록되지 않은 앱 헤더는 400, 본문의 app 이 헤더와 다르면(다른 앱 행세) 403")
    void unknownAppAndForgedBodyApp() throws Exception {
        perform(app(putSubscription(new TestDevice(), DEVICE_A), alice, "nope")).andExpect(status().isBadRequest());

        TestDevice device = new TestDevice();
        String forged = "{\"app\":\"blog\",\"deviceId\":\"" + DEVICE_A + "\"," + device.subscriptionJson().substring(1);
        perform(app(put("/api/v1/notify/subscriptions").contentType(MediaType.APPLICATION_JSON).content(forged), alice, "party"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOTIFY-403-02"));
        // 본문의 app 이 헤더와 같으면 문제없다(설계 문서의 요청 모양을 그대로 보내는 클라이언트)
        String same = "{\"app\":\"party\",\"deviceId\":\"" + DEVICE_A + "\"," + device.subscriptionJson().substring(1);
        perform(app(put("/api/v1/notify/subscriptions").contentType(MediaType.APPLICATION_JSON).content(same), alice, "party"))
                .andExpect(status().isOk());
        assertThat(rows("true")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 endpoint 를 다시 등록하면 한 행을 갱신한다(중복 없음)")
    void upsertIsIdempotent() throws Exception {
        TestDevice device = new TestDevice();
        perform(app(putSubscription(device, DEVICE_A), alice, "party")).andExpect(status().isOk());
        perform(app(putSubscription(device, DEVICE_B), alice, "party")).andExpect(status().isOk());
        assertThat(rows("true")).isEqualTo(1);
        assertThat(rows("device_id = ?", DEVICE_B)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 endpoint 가 다른 계정으로 오면 소유가 옮겨지고, 이전 소유자 앞 대기 작업은 버려진다")
    void ownershipTransfers() throws Exception {
        TestDevice device = new TestDevice();
        perform(app(putSubscription(device, DEVICE_A), alice, "party")).andExpect(status().isOk());
        UUID subscriptionId = jdbc.queryForObject("select id from push_subscriptions", UUID.class);
        UUID requestId = UUID.randomUUID();
        jdbc.update("insert into notify_requests (id, caller, idempotency_key, app_id, type_id, created_at) values (?, 'party', 'k', 'party', 't', now())", requestId);
        jdbc.update("""
                insert into delivery_jobs (id, request_id, subscription_id, app_id, topic, payload, status, next_attempt_at, expires_at, created_at, updated_at)
                values (?, ?, ?, 'party', 't', 'x'::bytea, 'PENDING', now(), now() + interval '1 day', now(), now())""", UUID.randomUUID(), requestId, subscriptionId);

        perform(app(putSubscription(device, DEVICE_A), bob, "party")).andExpect(status().isOk());

        assertThat(rows("user_id = ?", alice)).isZero();
        assertThat(rows("user_id = ?", bob)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from delivery_jobs", Integer.class)).as("이전 소유자의 알림이 새 소유자 기기로 가지 않는다").isZero();
    }

    @Test
    @DisplayName("(사용자, 앱)당 구독은 10개까지: 11번째를 등록하면 가장 오래된 것이 밀려난다")
    void perUserPerAppLimit() throws Exception {
        TestDevice first = null;
        for (int i = 0; i < 11; i++) {
            TestDevice device = new TestDevice();
            if (first == null) {
                first = device;
            }
            perform(app(putSubscription(device, "device-" + String.format("%08d", i)), alice, "party")).andExpect(status().isOk());
            com.doro.notify.support.TestClockConfig.CLOCK.advance(java.time.Duration.ofSeconds(1)); // created_at 순서가 분명하도록(시험용 시계는 멈춰 있다)
        }
        assertThat(rows("user_id = ? and app_id = 'party'", alice)).isEqualTo(10);
        assertThat(rows("endpoint = ?", first.endpoint)).as("가장 오래된 구독이 지워진다").isZero();
        // 다른 앱의 상한은 따로 센다
        perform(app(putSubscription(new TestDevice(), DEVICE_A), alice, "blog")).andExpect(status().isOk());
        assertThat(rows("user_id = ?", alice)).isEqualTo(11);
    }

    @Test
    @DisplayName("구독 해제는 내 것만: 남의 endpoint 를 알아도 지워지지 않고, 없어도 성공한다")
    void unsubscribeOwnOnly() throws Exception {
        TestDevice device = subscribe(alice, "party", DEVICE_A);

        perform(hub(delete("/api/v1/notify/subscriptions").contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"" + device.endpoint + "\"}"), bob))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.removed").value(0));
        assertThat(rows("user_id = ?", alice)).isEqualTo(1);

        perform(app(delete("/api/v1/notify/subscriptions").contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"" + device.endpoint + "\"}"), alice, "party"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.removed").value(1));
        assertThat(rows("true")).isZero();
        perform(hub(delete("/api/v1/notify/subscriptions").contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"" + device.endpoint + "\"}"), alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.removed").value(0));
    }

    @Test
    @DisplayName("기기 해제(로그아웃)는 이 기기의 내 모든 앱 구독을 지우고, 다른 기기·다른 사용자는 건드리지 않는다")
    void removeDevice() throws Exception {
        subscribe(alice, "party", DEVICE_A);
        subscribe(alice, "blog", DEVICE_A);
        subscribe(alice, "party", DEVICE_B);
        subscribe(bob, "party", DEVICE_A); // 같은 deviceId 문자열이어도 남의 것

        perform(hub(delete("/api/v1/notify/devices/" + DEVICE_A), alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.removed").value(2));

        assertThat(rows("user_id = ?", alice)).isEqualTo(1);
        assertThat(rows("user_id = ?", bob)).isEqualTo(1);
    }

    @Test
    @DisplayName("기기 목록은 기기별로 묶고 앱별 상태를 주되 endpoint·키·원문 User-Agent 는 싣지 않는다")
    void devicesListHidesSecrets() throws Exception {
        TestDevice party = new TestDevice();
        TestDevice blog = new TestDevice();
        String chrome = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";
        perform(app(putSubscription(party, DEVICE_A), alice, "party").header(HttpHeaders.USER_AGENT, chrome)).andExpect(status().isOk());
        perform(app(putSubscription(blog, DEVICE_A), alice, "blog").header(HttpHeaders.USER_AGENT, chrome)).andExpect(status().isOk());
        subscribe(alice, "party", DEVICE_B);
        subscribe(bob, "party", DEVICE_A);

        String body = perform(hub(get("/api/v1/notify/devices"), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(party.endpoint).doesNotContain(blog.endpoint).doesNotContain("p256dh").doesNotContain("\"auth\"")
                .doesNotContain("Mozilla").doesNotContain("endpoint");
        perform(hub(get("/api/v1/notify/devices"), alice))
                .andExpect(jsonPath("$.data.devices[?(@.deviceId=='" + DEVICE_A + "')].browser").value("Chrome · Android"))
                .andExpect(jsonPath("$.data.devices[?(@.deviceId=='" + DEVICE_A + "')].apps.length()").value(2));
    }

    @Test
    @DisplayName("허용되지 않은 endpoint(SSRF 후보)와 깨진 키는 400 이고 아무것도 저장하지 않는다")
    void rejectsBadEndpointAndKeys() throws Exception {
        TestDevice device = new TestDevice();
        for (String evil : new String[] {"https://169.254.169.254/latest", "https://evil.example/x", "http://localhost.evil.example/x", "ftp://localhost/x"}) {
            String body = "{\"deviceId\":\"" + DEVICE_A + "\",\"endpoint\":\"" + evil + "\",\"keys\":{\"p256dh\":\"x\",\"auth\":\"y\"}}";
            perform(app(put("/api/v1/notify/subscriptions").contentType(MediaType.APPLICATION_JSON).content(body), alice, "party"))
                    .andExpect(status().isBadRequest());
        }
        String badKeys = "{\"deviceId\":\"" + DEVICE_A + "\",\"endpoint\":\"" + device.endpoint + "\",\"keys\":{\"p256dh\":\"AAAA\",\"auth\":\"AAAA\"}}";
        perform(app(put("/api/v1/notify/subscriptions").contentType(MediaType.APPLICATION_JSON).content(badKeys), alice, "party"))
                .andExpect(status().isBadRequest()).andExpect(content().string(not(containsString(device.endpoint))));
        String badDevice = subscribeBody(device, "x y!");
        perform(app(put("/api/v1/notify/subscriptions").contentType(MediaType.APPLICATION_JSON).content(badDevice), alice, "party"))
                .andExpect(status().isBadRequest());
        String nonAscii = "{\"deviceId\":\"" + DEVICE_A + "\",\"endpoint\":\"http://localhost:1/한글\",\"keys\":{\"p256dh\":\"x\",\"auth\":\"y\"}}";
        perform(app(put("/api/v1/notify/subscriptions").contentType(MediaType.APPLICATION_JSON).content(nonAscii), alice, "party"))
                .andExpect(status().isBadRequest());
        assertThat(rows("true")).isZero();
    }

    @Test
    @DisplayName("config 는 VAPID 공개 키와 사용 가능 여부를 로그인 없이 준다(개인 키는 어디에도 없다)")
    void configExposesPublicKeyOnly() throws Exception {
        String body = perform(get("/api/v1/notify/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.publicKey").value(PushTestSupport.KEYS.publicKey))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(PushTestSupport.KEYS.privateKey);
    }

    @Test
    @DisplayName("로그인하지 않았거나 토큰이 잘못됐으면 401: 없음·위조·만료·모르는 OAuth 클라이언트·sub 없음")
    void requiresAuthentication() throws Exception {
        perform(get("/api/v1/notify/devices")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.success").value(false));
        for (String token : new String[] {DORO.forged(alice), DORO.expired(alice), DORO.tokenForClient(alice, "doro-unknown"), DORO.withoutSubject(), "not.a.jwt"}) {
            perform(get("/api/v1/notify/devices").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isUnauthorized());
        }
        perform(put("/api/v1/notify/subscriptions").header("X-Doro-Notify-App", "party").contentType(MediaType.APPLICATION_JSON)
                .content(subscribeBody(new TestDevice(), DEVICE_A))).andExpect(status().isUnauthorized());
        perform(get("/api/v1/notify/devices").header(HttpHeaders.AUTHORIZATION, "Bearer " + DORO.tokenWithoutClient(alice))).andExpect(status().isOk());
        perform(get("/api/v1/notify/devices").header(HttpHeaders.AUTHORIZATION, "Bearer " + DORO.tokenForClient(alice, FakeDoro.CLIENT_ID))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("본문이 너무 크면 413, JSON 이 아니면 400, 지원하지 않는 메서드는 405 로 표준 오류 규격을 쓴다")
    void errorEnvelope() throws Exception {
        String huge = "{\"deviceId\":\"" + DEVICE_A + "\",\"endpoint\":\"" + "a".repeat(20_000) + "\"}";
        perform(app(put("/api/v1/notify/subscriptions").contentType(MediaType.APPLICATION_JSON).content(huge), alice, "party"))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("COMMON-413-01"));
        perform(app(put("/api/v1/notify/subscriptions").contentType(MediaType.APPLICATION_JSON).content("{nope"), alice, "party"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.status").value(400));
        perform(app(post("/api/v1/notify/devices"), alice, "party")).andExpect(status().isMethodNotAllowed());
    }
}
