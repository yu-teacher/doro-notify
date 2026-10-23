package com.doro.notify.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.doro.notify.delivery.DeliveryWorker;
import com.doro.notify.push.PushTestSupport;
import com.doro.notify.push.PushTestSupport.TestDevice;
import com.doro.notify.support.AbstractNotifyTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 로그·오류 응답에 비밀(endpoint·p256dh·auth·VAPID 개인 키·알림 본문)이 남지 않고, traceId 가 요청 처리와 워커 로그에 이어진다. */
class ObservabilityTest extends AbstractNotifyTest {

    static final class Capture extends AppenderBase<ILoggingEvent> {
        final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            events.add(event);
        }
    }

    @Autowired
    DeliveryWorker worker;

    final UUID alice = UUID.randomUUID();
    Capture capture;
    Logger root;

    @BeforeEach
    void attach() {
        registerType("party", "comment", true);
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        capture = new Capture();
        capture.setContext(root.getLoggerContext());
        capture.start();
        root.addAppender(capture);
        root.getLoggerContext().getLogger("com.doro.notify").setLevel(Level.DEBUG);
    }

    @AfterEach
    void detach() {
        root.detachAppender(capture);
        root.getLoggerContext().getLogger("com.doro.notify").setLevel(null);
    }

    private String allLogText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : capture.events) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                text.append(event.getThrowableProxy().getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    @Test
    @DisplayName("등록·발송·실패(거절·404·일시 오류)·잘못된 입력을 거치는 동안 로그와 오류 응답에 endpoint·키·본문이 없다")
    void noSecretsInLogsOrErrors() throws Exception {
        TestDevice ok = new TestDevice();
        TestDevice gone = new TestDevice();
        TestDevice rejected = new TestDevice();
        TestDevice flaky = new TestDevice();
        StringBuilder responses = new StringBuilder();
        int n = 0;
        for (TestDevice device : new TestDevice[] {ok, gone, rejected, flaky}) {
            responses.append(perform(app(putSubscription(device, "device-" + String.format("%08d", n++)), alice, "party")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        }
        PushTestSupport.SERVICE.respondWith(gone.name, 410);
        PushTestSupport.SERVICE.respondWith(rejected.name, 400);
        PushTestSupport.SERVICE.respondWith(flaky.name, 503);

        perform(internal(post("/internal/v1/notifications"), PARTY_TOKEN).content("{\"idempotencyKey\":\"k1\",\"app\":\"party\",\"type\":\"comment\",\"recipients\":[\""
                + alice + "\"],\"title\":\"비밀제목\",\"body\":\"비밀본문\",\"tag\":\"pin:9\",\"url\":\"/party/?x=1\"}")).andExpect(status().isOk());
        worker.runUntilIdle();
        TestClockCloser.pass(Duration.ofMinutes(1));
        worker.runUntilIdle();
        perform(app(post("/api/v1/notify/test"), alice, "party"));
        // 잘못된 입력들: 오류 응답에도 값이 되풀이되지 않아야 한다
        String evilEndpoint = "https://evil.example/secret-endpoint-value";
        responses.append(perform(app(put("/api/v1/notify/subscriptions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"device-zzzzzzzz\",\"endpoint\":\"" + evilEndpoint + "\",\"keys\":{\"p256dh\":\"secret-p256dh-value\",\"auth\":\"secret-auth-value\"}}"), alice, "party"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString());
        responses.append(perform(app(put("/api/v1/notify/subscriptions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"device-zzzzzzzz\",\"endpoint\":\"" + ok.endpoint + "\",\"keys\":{\"p256dh\":\"secret-p256dh-value\",\"auth\":\"secret-auth-value\"}}"), alice, "party"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString());
        perform(app(delete("/api/v1/notify/subscriptions").contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"" + gone.endpoint + "\"}"), alice, "party"));

        String logs = allLogText();
        assertThat(capture.events).as("로그가 실제로 수집됐다").isNotEmpty();
        assertThat(logs).contains("Delivery sent");
        for (TestDevice device : new TestDevice[] {ok, gone, rejected, flaky}) {
            String subscription = device.subscriptionJson();
            for (String secret : new String[] {device.endpoint, JsonPath.<String>read(subscription, "$.keys.p256dh"), JsonPath.<String>read(subscription, "$.keys.auth")}) {
                assertThat(logs).doesNotContain(secret);
                assertThat(responses.toString()).doesNotContain(secret);
            }
        }
        for (String secret : new String[] {PushTestSupport.KEYS.privateKey, "비밀제목", "비밀본문", evilEndpoint, "secret-p256dh-value", "secret-auth-value", PARTY_TOKEN}) {
            assertThat(logs).as(secret).doesNotContain(secret);
            assertThat(responses.toString()).as(secret).doesNotContain(secret);
        }
    }

    @Test
    @DisplayName("X-Trace-Id 는 응답 헤더로 돌아오고 요청 기록·발송 작업에 저장되며, 워커의 로그 줄이 같은 traceId 를 MDC 로 갖는다")
    void traceIdFollowsRequestIntoWorkerLogs() throws Exception {
        TestDevice device = subscribe(alice, "party", "device-aaaaaaaa");
        perform(internal(post("/internal/v1/notifications"), PARTY_TOKEN).header("X-Trace-Id", "trace-abc-123")
                .content("{\"idempotencyKey\":\"k1\",\"app\":\"party\",\"type\":\"comment\",\"recipients\":[\"" + alice + "\"],\"title\":\"t\",\"body\":\"b\",\"url\":\"/party/\"}"))
                .andExpect(status().isOk()).andExpect(header().string("X-Trace-Id", "trace-abc-123"));
        assertThat(jdbc.queryForObject("select trace_id from notify_requests", String.class)).isEqualTo("trace-abc-123");
        assertThat(jdbc.queryForObject("select trace_id from delivery_jobs", String.class)).isEqualTo("trace-abc-123");

        capture.events.clear();
        worker.runUntilIdle();

        assertThat(device.received()).hasSize(1);
        assertThat(capture.events).filteredOn(e -> e.getFormattedMessage().startsWith("Delivery sent"))
                .singleElement().satisfies(e -> assertThat(e.getMDCPropertyMap()).containsEntry("traceId", "trace-abc-123"));
    }

    /** 시험용 시계를 한 줄로 앞당긴다. */
    private static final class TestClockCloser {
        static void pass(Duration duration) {
            com.doro.notify.support.TestClockConfig.CLOCK.advance(duration);
        }
    }
}
