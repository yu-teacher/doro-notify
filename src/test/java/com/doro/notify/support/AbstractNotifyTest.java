package com.doro.notify.support;

import com.doro.notify.push.PushTestKeys;
import com.doro.notify.push.PushTestSupport;
import com.hunnit_beasts.doro.sdk.security.jwks.JwksKeyProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 통합 시험의 공통 기반: 전용 Postgres 스키마, 가짜 Doro(토큰 발급), 가짜 IAM 내부 API(탈퇴 동기화), 가짜 푸시 서비스, 시험용 시계.
 * 주기 작업(발송 워커·정리·탈퇴 동기화)은 끄고 각 시험이 직접 부른다. 세션 폐기 확인도 끈다(IAM 이 없다).
 * 이 클래스를 확장하는 시험은 컨텍스트 하나(= 스키마 하나)를 공유하므로 시험마다 표를 비운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public abstract class AbstractNotifyTest {

    protected static final FakeDoro DORO = new FakeDoro();
    protected static final FakeIamInternal IAM = new FakeIamInternal();

    // 내부 API 호출자 토큰(32자 이상). 이름은 알림 앱 id 와 같다.
    protected static final String PARTY_TOKEN = "party-test-token-" + "p".repeat(32);
    protected static final String BLOG_TOKEN = "blog-test-token-" + "b".repeat(32);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String url = IsolatedSchema.createAndGetUrl();
        PushTestKeys keys = PushTestSupport.KEYS;
        registry.add("spring.datasource.url", () -> url);
        registry.add("doro.iam.revocation-check", () -> "OFF");
        registry.add("doro.iam.oauth-client-ids", () -> "doro-party,doro-blog");
        registry.add("doro.iam.jwks-prefetch", () -> "false");
        registry.add("doro.iam.internal-url", IAM::url);
        registry.add("doro.iam.internal-token", () -> FakeIamInternal.TOKEN);
        registry.add("notify.internal-auth.mode", () -> "ENFORCE");
        registry.add("notify.internal-auth.tokens", () -> "party:" + PARTY_TOKEN + ",blog:" + BLOG_TOKEN);
        registry.add("notify.push.vapid-public-key", () -> keys.publicKey);
        registry.add("notify.push.vapid-private-key", () -> keys.privateKey);
        registry.add("notify.push.subject", () -> "mailto:ops@example.test");
        registry.add("notify.push.allowed-hosts", () -> "localhost");
        registry.add("notify.push.require-https", () -> "false");
        registry.add("notify.push.request-timeout", () -> "PT3S");
        registry.add("notify.worker.enabled", () -> "false");
        registry.add("notify.retention.enabled", () -> "false");
        registry.add("notify.deleted-sync.enabled", () -> "false");
    }

    @AfterAll
    static void noop() {
        // 가짜 서버들은 JVM 이 끝날 때 함께 사라진다(여러 시험 클래스가 컨텍스트와 함께 공유한다)
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    private JwksKeyProvider jwks;

    @BeforeEach
    void resetState() {
        jwks.registerKey(FakeDoro.KID, DORO.publicKey());
        TestClockConfig.CLOCK.reset();
        IAM.clear();
        PushTestSupport.SERVICE.reset();
        jdbc.execute("TRUNCATE delivery_jobs, notify_request_recipients, notify_requests, push_subscriptions, notify_preferences, notify_deleted_users, iam_sync_state, notify_types");
    }

    // -- 요청 도우미

    protected static String bearer(UUID user) {
        return "Bearer " + DORO.token(user);
    }

    protected MockHttpServletRequestBuilder asJson(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** 앱 경로(게이트웨이가 X-Doro-Notify-App 을 붙인 요청)로 부른다. */
    protected MockHttpServletRequestBuilder app(MockHttpServletRequestBuilder request, UUID user, String app) {
        return request.header(HttpHeaders.AUTHORIZATION, bearer(user)).header("X-Doro-Notify-App", app);
    }

    /** 허브 경로(Bearer 직접, 앱 헤더 없음)로 부른다. */
    protected MockHttpServletRequestBuilder hub(MockHttpServletRequestBuilder request, UUID user) {
        return request.header(HttpHeaders.AUTHORIZATION, bearer(user));
    }

    protected static MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request, String token) {
        return request.header("X-Doro-Service-Token", token).contentType(MediaType.APPLICATION_JSON);
    }

    protected ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request);
    }

    protected static MockHttpServletRequestBuilder putSubscription(PushTestSupport.TestDevice device, String deviceId) {
        return put("/api/v1/notify/subscriptions").contentType(MediaType.APPLICATION_JSON).content(subscribeBody(device, deviceId));
    }

    protected static String subscribeBody(PushTestSupport.TestDevice device, String deviceId) {
        String sub = device.subscriptionJson();
        return "{\"deviceId\":\"" + deviceId + "\"," + sub.substring(1);
    }

    /** 이 사용자의 이 앱으로 기기를 등록한다(성공해야 한다). */
    protected PushTestSupport.TestDevice subscribe(UUID user, String app, String deviceId) throws Exception {
        PushTestSupport.TestDevice device = new PushTestSupport.TestDevice();
        mvc.perform(app(putSubscription(device, deviceId), user, app)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        return device;
    }

    protected void registerType(String app, String type, boolean defaultOn) {
        jdbc.update("""
                INSERT INTO notify_types (app_id, type_id, label, description, default_on, deprecated)
                VALUES (?, ?, ?, '', ?, FALSE) ON CONFLICT (app_id, type_id) DO UPDATE SET default_on = EXCLUDED.default_on, deprecated = FALSE""",
                app, type, type + " 라벨", defaultOn);
    }

    protected static MockHttpServletRequestBuilder getReq(String path) {
        return get(path);
    }

    protected static MockHttpServletRequestBuilder postReq(String path) {
        return post(path);
    }

    protected static MockHttpServletRequestBuilder deleteReq(String path) {
        return delete(path);
    }
}
