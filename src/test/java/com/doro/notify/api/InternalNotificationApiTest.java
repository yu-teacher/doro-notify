package com.doro.notify.api;

import com.doro.notify.push.PushTestSupport.TestDevice;
import com.doro.notify.support.AbstractNotifyTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 내부 API: 호출자 인증, app 위조 403, 멱등, 수신자별 판정, url 범위 검증, 만료·dryRun·상한, 종류 등록. */
class InternalNotificationApiTest extends AbstractNotifyTest {

    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();
    final UUID carol = UUID.randomUUID();

    @BeforeEach
    void types() {
        registerType("party", "comment", true);
        registerType("party", "share", false);
        registerType("blog", "comment", true);
    }

    static String json(String key, String app, String type, UUID[] recipients, String url, String extra) {
        String ids = Arrays.stream(recipients).map(u -> "\"" + u + "\"").collect(Collectors.joining(","));
        return "{\"idempotencyKey\":\"" + key + "\",\"app\":\"" + app + "\",\"type\":\"" + type + "\",\"recipients\":[" + ids + "],"
                + "\"title\":\"핀 이름\",\"body\":\"작성자: 댓글\",\"tag\":\"pin:1\",\"url\":\"" + url + "\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    ResultActions notify(String token, String body) throws Exception {
        return perform(internal(post("/internal/v1/notifications"), token).content(body));
    }

    String valid(String key, UUID... recipients) {
        return json(key, "party", "comment", recipients, "/party/?map=1&pin=2", "");
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject("select count(*) from " + sql, Integer.class, args);
    }

    // -- 호출자 인증

    @Test
    @DisplayName("서비스 토큰이 없거나 틀리면 401 이고 아무것도 처리하지 않는다")
    void rejectsMissingOrWrongToken() throws Exception {
        perform(post("/internal/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(valid("k1", alice)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.success").value(false));
        notify("wrong-token-" + "w".repeat(40), valid("k1", alice)).andExpect(status().isUnauthorized());
        notify(PARTY_TOKEN.substring(0, 20), valid("k1", alice)).andExpect(status().isUnauthorized());
        perform(get("/internal/v1/stats")).andExpect(status().isUnauthorized());
        assertThat(count("notify_requests")).isZero();
    }

    @Test
    @DisplayName("게이트웨이를 거친 요청(X-Forwarded-For / X-Real-IP)은 토큰이 맞아도 404 다")
    void gatewayTrafficGets404() throws Exception {
        perform(internal(post("/internal/v1/notifications"), PARTY_TOKEN).header("X-Forwarded-For", "203.0.113.9").content(valid("k1", alice)))
                .andExpect(status().isNotFound());
        perform(internal(get("/internal/v1/stats"), PARTY_TOKEN).header("X-Real-IP", "203.0.113.9")).andExpect(status().isNotFound());
        assertThat(count("notify_requests")).isZero();
    }

    @Test
    @DisplayName("경로를 비틀어도(퍼센트 인코딩·세미콜론·이중 슬래시) 내부 인증을 우회하지 못한다")
    void pathTricksDoNotBypass() throws Exception {
        for (String path : new String[] {"/internal/v1/notifications", "/internal//v1/notifications", "/internal/v1/%6Eotifications", "/internal/v1/notifications;x=1"}) {
            int code = perform(post(path).contentType(MediaType.APPLICATION_JSON).content(valid("k-" + path.hashCode(), alice))).andReturn().getResponse().getStatus();
            assertThat(code).as(path).isIn(401, 404);
        }
        assertThat(count("notify_requests")).isZero();
    }

    @Test
    @DisplayName("호출자는 자기 이름과 같은 app 으로만 요청할 수 있다: 다른 app 이면 403")
    void callerCanOnlyUseOwnApp() throws Exception {
        notify(BLOG_TOKEN, valid("k1", alice)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOTIFY-403-02"));
        notify(PARTY_TOKEN, json("k1", "blog", "comment", new UUID[] {alice}, "/blog/p/1", "")).andExpect(status().isForbidden());
        assertThat(count("notify_requests")).isZero();
        notify(BLOG_TOKEN, json("k1", "blog", "comment", new UUID[] {alice}, "/blog/p/1", "")).andExpect(status().isOk());

        // 종류 등록도 같다
        perform(internal(put("/internal/v1/apps/blog/types"), PARTY_TOKEN).content("{\"types\":[{\"type\":\"x\",\"label\":\"X\"}]}")).andExpect(status().isForbidden());
    }

    // -- 판정과 멱등

    @Test
    @DisplayName("수신자별로 판정한다: 기기 있음(accepted)·종류 꺼 둠(suppressed)·기기 없음(noSubscription)")
    void judgesPerRecipient() throws Exception {
        subscribe(alice, "party", "device-aaaaaaaa");
        subscribe(alice, "party", "device-aaaaaaab");
        subscribe(bob, "party", "device-bbbbbbbb");
        perform(hub(put("/api/v1/notify/preferences").contentType(MediaType.APPLICATION_JSON)
                .content("{\"changes\":[{\"app\":\"party\",\"type\":\"comment\",\"enabled\":false}]}"), bob)).andExpect(status().isOk());

        notify(PARTY_TOKEN, valid("k1", alice, bob, carol))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.outcome").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.accepted").value(1))
                .andExpect(jsonPath("$.data.suppressed").value(1))
                .andExpect(jsonPath("$.data.noSubscription").value(1))
                .andExpect(jsonPath("$.data.devices").value(2));

        assertThat(count("delivery_jobs where status = 'PENDING'")).isEqualTo(2);
        assertThat(count("notify_request_recipients where outcome = 'ACCEPTED'")).isEqualTo(1);
        assertThat(count("notify_request_recipients where outcome = 'SUPPRESSED_PREF'")).isEqualTo(1);
        assertThat(count("notify_request_recipients where outcome = 'NO_SUBSCRIPTION'")).isEqualTo(1);
        // 알림 내용(제목·본문)은 요청 기록에 영구 저장하지 않는다
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'notify_requests' and table_schema = current_schema()", String.class))
                .doesNotContain("title", "body", "payload");
    }

    @Test
    @DisplayName("종류의 기본값이 꺼짐이면 설정 행이 없어도 꺼진 것으로 본다(share)")
    void defaultOffIsSuppressed() throws Exception {
        subscribe(alice, "party", "device-aaaaaaaa");
        notify(PARTY_TOKEN, json("k1", "party", "share", new UUID[] {alice}, "/party/", ""))
                .andExpect(jsonPath("$.data.suppressed").value(1)).andExpect(jsonPath("$.data.accepted").value(0));
        assertThat(count("delivery_jobs")).isZero();
    }

    @Test
    @DisplayName("다른 앱의 구독으로는 보내지 않는다: blog 구독만 있는 사용자는 party 알림에서 기기 없음")
    void onlyThatAppsSubscriptions() throws Exception {
        subscribe(alice, "blog", "device-aaaaaaaa");
        notify(PARTY_TOKEN, valid("k1", alice)).andExpect(jsonPath("$.data.noSubscription").value(1));
        assertThat(count("delivery_jobs")).isZero();
    }

    @Test
    @DisplayName("같은 (호출자, 멱등 키) 재요청은 200 DUPLICATE 이고 아무것도 새로 만들지 않는다. 다른 호출자의 같은 키는 별개다")
    void idempotency() throws Exception {
        subscribe(alice, "party", "device-aaaaaaaa");
        subscribe(alice, "blog", "device-aaaaaaaa");
        notify(PARTY_TOKEN, valid("same", alice)).andExpect(jsonPath("$.data.outcome").value("ACCEPTED"));
        notify(PARTY_TOKEN, valid("same", alice, bob)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("DUPLICATE"))
                .andExpect(jsonPath("$.data.accepted").value(1))
                .andExpect(jsonPath("$.data.devices").value(1));
        assertThat(count("notify_requests")).isEqualTo(1);
        assertThat(count("delivery_jobs")).isEqualTo(1);

        notify(BLOG_TOKEN, json("same", "blog", "comment", new UUID[] {alice}, "/blog/p/1", "")).andExpect(jsonPath("$.data.outcome").value("ACCEPTED"));
        assertThat(count("notify_requests")).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 키로 동시에 보내도 요청은 하나만 처리된다")
    void concurrentSameKey() throws Exception {
        subscribe(alice, "party", "device-aaaaaaaa");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 6; i++) {
                futures.add(pool.submit(() -> notify(PARTY_TOKEN, valid("race", alice)).andReturn().getResponse().getContentAsString()));
            }
            long accepted = 0;
            for (var f : futures) {
                if (f.get().contains("\"ACCEPTED\"")) {
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("notify_requests")).isEqualTo(1);
        assertThat(count("delivery_jobs")).isEqualTo(1);
    }

    @Test
    @DisplayName("수신자 목록의 중복은 한 명으로 센다")
    void dedupesRecipients() throws Exception {
        subscribe(alice, "party", "device-aaaaaaaa");
        notify(PARTY_TOKEN, valid("k1", alice, alice, alice)).andExpect(jsonPath("$.data.accepted").value(1)).andExpect(jsonPath("$.data.devices").value(1));
    }

    // -- 입력 검증

    @Test
    @DisplayName("url 은 등록된 scope_path 안의 같은 출처 경로만: 절대 주소·//호스트·범위 밖·.. 우회·역슬래시는 400")
    void urlMustStayInScope() throws Exception {
        subscribe(alice, "party", "device-aaaaaaaa");
        String[] bad = {"https://evil.example/party/", "//evil.example/party/", "/blog/post/1", "/party", "/partyx/", "/party/../blog/x", "/party/%2e%2e/blog/x",
                "/party/..%2fblog", "/party/./x", "party/x", "javascript:alert(1)", "/party/\\\\evil", "/party/ x", "", "/party/%5c%5cevil", "http:/party/"};
        int n = 0;
        for (String url : bad) {
            notify(PARTY_TOKEN, json("bad-" + n++, "party", "comment", new UUID[] {alice}, url, ""))
                    .andExpect(status().isBadRequest());
        }
        assertThat(count("notify_requests")).isZero();
        String[] good = {"/party/", "/party/?map=1&pin=2#c", "/party/friends", "/party/maps/한글?q=%ED%95%9C"};
        for (String url : good) {
            notify(PARTY_TOKEN, json("good-" + n++, "party", "comment", new UUID[] {alice}, url, "")).andExpect(status().isOk());
        }
        // icon 도 같은 범위 규칙
        notify(PARTY_TOKEN, json("icon-bad", "party", "comment", new UUID[] {alice}, "/party/", "\"icon\":\"https://evil.example/i.png\"")).andExpect(status().isBadRequest());
        notify(PARTY_TOKEN, json("icon-ok", "party", "comment", new UUID[] {alice}, "/party/", "\"icon\":\"/party/icon-192.png\"")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("이미 만료된 알림(expiresAt 이 과거)은 오류 없이 EXPIRED 로 버리고 아무것도 저장하지 않는다")
    void expiredAtArrivalIsDropped() throws Exception {
        subscribe(alice, "party", "device-aaaaaaaa");
        String past = Instant.now().minusSeconds(60).toString();
        notify(PARTY_TOKEN, json("k1", "party", "comment", new UUID[] {alice}, "/party/", "\"expiresAt\":\"" + past + "\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("EXPIRED")).andExpect(jsonPath("$.data.accepted").value(0));
        assertThat(count("notify_requests")).isZero();
        assertThat(count("delivery_jobs")).isZero();
    }

    @Test
    @DisplayName("expiresAt 과 ttlSeconds 중 빠른 쪽이 작업의 만료 시각이 된다")
    void jobExpiryIsEarlierOfTtlAndExpiresAt() throws Exception {
        subscribe(alice, "party", "device-aaaaaaaa");
        Instant soon = Instant.now().plus(Duration.ofMinutes(5));
        notify(PARTY_TOKEN, json("k1", "party", "comment", new UUID[] {alice}, "/party/", "\"ttlSeconds\":86400,\"expiresAt\":\"" + soon + "\""));
        notify(PARTY_TOKEN, json("k2", "party", "comment", new UUID[] {bob}, "/party/", "\"ttlSeconds\":60"));
        Instant expires = jdbc.queryForObject("select expires_at from delivery_jobs", Instant.class);
        assertThat(Duration.between(Instant.now(), expires).toMinutes()).isBetween(3L, 5L);
    }

    @Test
    @DisplayName("dryRun 은 판정만 하고 아무것도 저장하지 않으며 멱등 키도 쓰지 않는다")
    void dryRunOnlyJudges() throws Exception {
        subscribe(alice, "party", "device-aaaaaaaa");
        String dry = json("k1", "party", "comment", new UUID[] {alice, bob}, "/party/", "\"dryRun\":true");
        notify(PARTY_TOKEN, dry).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("DRY_RUN"))
                .andExpect(jsonPath("$.data.accepted").value(1))
                .andExpect(jsonPath("$.data.noSubscription").value(1))
                .andExpect(jsonPath("$.data.devices").value(1));
        assertThat(count("notify_requests")).isZero();
        assertThat(count("delivery_jobs")).isZero();
        // 같은 키로 이어서 보내는 진짜 요청은 DUPLICATE 가 아니다(이관 단계의 섀도 → 실발송)
        notify(PARTY_TOKEN, valid("k1", alice)).andExpect(jsonPath("$.data.outcome").value("ACCEPTED"));
        assertThat(count("delivery_jobs")).isEqualTo(1);
    }

    @Test
    @DisplayName("상한: 수신자 수·제목·본문·ttl·urgency·페이로드 크기를 넘으면 400")
    void limits() throws Exception {
        UUID[] many = new UUID[501];
        for (int i = 0; i < many.length; i++) {
            many[i] = UUID.randomUUID();
        }
        notify(PARTY_TOKEN, json("k1", "party", "comment", many, "/party/", "")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOTIFY-400-04"));
        UUID[] exactly = Arrays.copyOf(many, 500);
        notify(PARTY_TOKEN, json("k2", "party", "comment", exactly, "/party/", "")).andExpect(status().isOk());

        notify(PARTY_TOKEN, valid("k3", alice).replace("핀 이름", "가".repeat(121))).andExpect(status().isBadRequest());
        notify(PARTY_TOKEN, valid("k4", alice).replace("작성자: 댓글", "가".repeat(501))).andExpect(status().isBadRequest());
        notify(PARTY_TOKEN, json("k5", "party", "comment", new UUID[] {alice}, "/party/", "\"ttlSeconds\":0")).andExpect(status().isBadRequest());
        notify(PARTY_TOKEN, json("k6", "party", "comment", new UUID[] {alice}, "/party/", "\"ttlSeconds\":999999999")).andExpect(status().isBadRequest());
        notify(PARTY_TOKEN, json("k7", "party", "comment", new UUID[] {alice}, "/party/", "\"urgency\":\"extreme\"")).andExpect(status().isBadRequest());
        notify(PARTY_TOKEN, json("k8", "party", "comment", new UUID[] {alice}, "/party/", "").replace("\"recipients\":[\"" + alice + "\"]", "\"recipients\":[]")).andExpect(status().isBadRequest());
        notify(PARTY_TOKEN, json("k9", "party", "comment", new UUID[] {alice}, "/party/", "").replace("\"idempotencyKey\":\"k9\"", "\"idempotencyKey\":\"\"")).andExpect(status().isBadRequest());
        notify(PARTY_TOKEN, "{not json").andExpect(status().isBadRequest());
        notify(PARTY_TOKEN, json("k10", "party", "comment", new UUID[] {alice}, "/party/", "").replace(alice.toString(), "not-a-uuid")).andExpect(status().isBadRequest());
        // 본문 글자 수는 맞지만 UTF-8 바이트가 푸시 한도(약 4KB)를 넘는 경우: 제목 120 + 본문 500 + tag 128 + url·icon 각 1024 를 3바이트 글자로 채운다
        String longPath = "/party/" + "가".repeat(1000);
        notify(PARTY_TOKEN, json("k11", "party", "comment", new UUID[] {alice}, longPath,
                "\"icon\":\"" + longPath + "\"").replace("핀 이름", "가".repeat(120)).replace("작성자: 댓글", "나".repeat(500)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOTIFY-400-05"));
        assertThat(count("notify_requests")).isEqualTo(1);
    }

    @Test
    @DisplayName("등록되지 않은 앱·종류는 400(앱은 호출자 이름과 같아야 하므로 종류부터 확인한다)")
    void unknownTypeOrApp() throws Exception {
        notify(PARTY_TOKEN, json("k1", "party", "nope", new UUID[] {alice}, "/party/", "")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOTIFY-400-03"));
        notify(PARTY_TOKEN, json("k2", "party", "Bad Type!", new UUID[] {alice}, "/party/", "")).andExpect(status().isBadRequest());
    }

    // -- 같은 tag 합치기

    @Test
    @DisplayName("같은 구독의 같은 tag 로 대기 중인 옛 알림은 새 알림이 대신한다")
    void sameTagSupersedesPending() throws Exception {
        subscribe(alice, "party", "device-aaaaaaaa");
        notify(PARTY_TOKEN, valid("k1", alice));
        notify(PARTY_TOKEN, valid("k2", alice));
        assertThat(count("delivery_jobs where status = 'PENDING'")).isEqualTo(1);
        assertThat(count("delivery_jobs where status = 'DROPPED' and payload is null")).isEqualTo(1);
    }

    // -- 종류 등록과 상태

    @Test
    @DisplayName("종류 등록은 멱등 upsert 이고, 목록에서 빠진 종류는 지우지 않고 deprecated 로 표시한다")
    void registersTypesAndDeprecates() throws Exception {
        String two = "{\"types\":[{\"type\":\"comment\",\"label\":\"새 댓글\",\"description\":\"내 글에 댓글이 달리면\"},{\"type\":\"friend\",\"label\":\"친구 요청\",\"defaultOn\":false}]}";
        perform(internal(put("/internal/v1/apps/party/types"), PARTY_TOKEN).content(two)).andExpect(status().isOk()).andExpect(jsonPath("$.data.registered").value(2));
        perform(internal(put("/internal/v1/apps/party/types"), PARTY_TOKEN).content(two)).andExpect(status().isOk());
        assertThat(count("notify_types where app_id = 'party' and deprecated = false")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select label from notify_types where app_id = 'party' and type_id = 'comment'", String.class)).isEqualTo("새 댓글");
        assertThat(jdbc.queryForObject("select default_on from notify_types where app_id = 'party' and type_id = 'friend'", Boolean.class)).isFalse();
        // share 는 위 목록에 없으므로 deprecated(삭제 아님)
        assertThat(jdbc.queryForObject("select deprecated from notify_types where app_id = 'party' and type_id = 'share'", Boolean.class)).isTrue();
        // 다른 앱의 종류는 건드리지 않는다
        assertThat(jdbc.queryForObject("select deprecated from notify_types where app_id = 'blog' and type_id = 'comment'", Boolean.class)).isFalse();

        // 다시 목록에 넣으면 되살아난다
        perform(internal(put("/internal/v1/apps/party/types"), PARTY_TOKEN).content("{\"types\":[{\"type\":\"share\",\"label\":\"공유\"}]}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select deprecated from notify_types where app_id = 'party' and type_id = 'share'", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select deprecated from notify_types where app_id = 'party' and type_id = 'comment'", Boolean.class)).isTrue();

        for (String bad : new String[] {"{\"types\":[]}", "{\"types\":[{\"type\":\"A B\",\"label\":\"x\"}]}", "{\"types\":[{\"type\":\"a\",\"label\":\"\"}]}",
                "{\"types\":[{\"type\":\"a\",\"label\":\"x\"},{\"type\":\"a\",\"label\":\"y\"}]}"}) {
            perform(internal(put("/internal/v1/apps/party/types"), PARTY_TOKEN).content(bad)).andExpect(status().isBadRequest());
        }
        perform(internal(put("/internal/v1/apps/nope/types"), PARTY_TOKEN).content(two)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("종류 등록에 scopePath 를 실으면 그 앱의 알림 주소 범위가 바뀐다(공개 경로가 옮겨질 때). 이상한 경로는 거절한다")
    void servicesCanMoveTheirOwnScopePath() throws Exception {
        String types = "\"types\":[{\"type\":\"comment\",\"label\":\"댓글\"}]";
        // 원래 범위는 /blog/ 라서 /dev/blog/ 주소는 거절된다
        notify(BLOG_TOKEN, json("k1", "blog", "comment", new UUID[] {alice}, "/dev/blog/@u/p", "")).andExpect(status().isBadRequest());
        perform(internal(put("/internal/v1/apps/blog/types"), BLOG_TOKEN).content("{" + types + ",\"scopePath\":\"/dev/blog/\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select scope_path from notify_apps where app_id = 'blog'", String.class)).isEqualTo("/dev/blog/");
        notify(BLOG_TOKEN, json("k2", "blog", "comment", new UUID[] {alice}, "/dev/blog/@u/p", "")).andExpect(status().isOk());
        notify(BLOG_TOKEN, json("k3", "blog", "comment", new UUID[] {alice}, "/blog/@u/p", "")).andExpect(status().isBadRequest());   // 옛 범위는 더 이상 아니다

        for (String bad : new String[] {"/", "dev/blog/", "/dev/blog", "/dev/../blog/", "//evil/", "/a b/", "/%2e%2e/"}) {
            perform(internal(put("/internal/v1/apps/blog/types"), BLOG_TOKEN).content("{" + types + ",\"scopePath\":\"" + bad + "\"}")).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("select scope_path from notify_apps where app_id = 'blog'", String.class)).isEqualTo("/dev/blog/");
        // 다른 앱의 범위는 바꿀 수 없다(호출자 이름과 app 이 같아야 한다)
        perform(internal(put("/internal/v1/apps/party/types"), BLOG_TOKEN).content("{" + types + ",\"scopePath\":\"/x/\"}")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select scope_path from notify_apps where app_id = 'party'", String.class)).isEqualTo("/party/");
        // 시험이 서로 영향을 주지 않게 되돌려 둔다
        jdbc.update("update notify_apps set scope_path = '/blog/' where app_id = 'blog'");
    }

    @Test
    @DisplayName("stats 는 상태별 건수와 가장 오래된 PENDING 의 나이만 주고 알림 내용은 없다")
    void stats() throws Exception {
        subscribe(alice, "party", "device-aaaaaaaa");
        notify(PARTY_TOKEN, valid("k1", alice));
        com.doro.notify.support.TestClockConfig.CLOCK.advance(Duration.ofSeconds(90));
        String body = perform(internal(get("/internal/v1/stats"), BLOG_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobsByStatus.PENDING").value(1))
                .andExpect(jsonPath("$.data.jobsByStatus.SENT").value(0))
                .andExpect(jsonPath("$.data.oldestPendingAgeSeconds").value(org.hamcrest.Matchers.greaterThanOrEqualTo(90)))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("작성자").doesNotContain("핀 이름");
    }

    @Test
    @DisplayName("TestDevice 도 푸시 서비스에서 실제 기기처럼 동작한다(시험 도구 점검)")
    void testDeviceWorks() {
        TestDevice device = new TestDevice();
        assertThat(device.endpoint).startsWith("http://localhost:");
    }
}
