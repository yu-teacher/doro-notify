package com.doro.notify.api;

import com.doro.notify.delivery.DeliveryWorker;
import com.doro.notify.push.PushTestSupport.TestDevice;
import com.doro.notify.support.FakeDoro;
import com.doro.notify.support.IsolatedSchema;
import com.doro.notify.support.TestClockConfig;
import com.hunnit_beasts.doro.sdk.security.jwks.JwksKeyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** VAPID 키가 없으면 푸시만 꺼지고 서비스는 정상 기동한다(헬스·설정·기기 목록·설정 API 는 그대로). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class PushDisabledTest {

    private static final FakeDoro DORO = new FakeDoro();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String url = IsolatedSchema.createAndGetUrl();
        registry.add("spring.datasource.url", () -> url);
        registry.add("doro.iam.revocation-check", () -> "OFF");
        registry.add("doro.iam.oauth-client-ids", () -> "doro-party");
        registry.add("doro.iam.jwks-prefetch", () -> "false");
        registry.add("notify.internal-auth.tokens", () -> "party:" + "p".repeat(40));
        registry.add("notify.push.vapid-public-key", () -> "");
        registry.add("notify.push.vapid-private-key", () -> "");
        registry.add("notify.push.subject", () -> "");
        registry.add("notify.worker.enabled", () -> "false");
        registry.add("notify.retention.enabled", () -> "false");
        registry.add("notify.deleted-sync.enabled", () -> "false");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JwksKeyProvider jwks;
    @Autowired
    DeliveryWorker worker;

    final UUID alice = UUID.randomUUID();

    @BeforeEach
    void key() {
        jwks.registerKey(FakeDoro.KID, DORO.publicKey());
    }

    @Test
    @DisplayName("헬스는 UP, config 는 enabled=false 이고 공개 키가 비어 있다")
    void bootsWithoutKeys() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/api/v1/notify/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false)).andExpect(jsonPath("$.data.publicKey").value(""));
    }

    @Test
    @DisplayName("구독 등록과 시험 알림은 503, 기기 목록·설정은 그대로 동작하고 워커는 아무것도 하지 않는다")
    void onlyPushIsOff() throws Exception {
        String body = "{\"deviceId\":\"device-aaaaaaaa\"," + new TestDevice().subscriptionJson().substring(1);
        mvc.perform(put("/api/v1/notify/subscriptions").header(HttpHeaders.AUTHORIZATION, "Bearer " + DORO.token(alice)).header("X-Doro-Notify-App", "party")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("NOTIFY-503-01"));
        mvc.perform(post("/api/v1/notify/test").header(HttpHeaders.AUTHORIZATION, "Bearer " + DORO.token(alice))).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/notify/devices").header(HttpHeaders.AUTHORIZATION, "Bearer " + DORO.token(alice))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/notify/preferences").header(HttpHeaders.AUTHORIZATION, "Bearer " + DORO.token(alice))).andExpect(status().isOk());
        assertThat(worker.runUntilIdle()).isZero();
    }
}
