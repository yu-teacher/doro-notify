package com.doro.notify.push;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WebPushClientTest {

    private HttpServer server;
    private final AtomicInteger responseStatus = new AtomicInteger(201);
    private final AtomicReference<com.sun.net.httpserver.Headers> seenHeaders = new AtomicReference<>();
    private final AtomicReference<byte[]> seenBody = new AtomicReference<>();
    private WebPushClient client;
    private PushTestKeys keys;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push", exchange -> {
            seenHeaders.set(exchange.getRequestHeaders());
            seenBody.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(responseStatus.get(), -1);
            exchange.close();
        });
        server.start();
        keys = PushTestKeys.generate();
        client = new WebPushClient(HttpClient.newHttpClient(), VapidSigner.from(keys.properties(), Clock.systemUTC()), Duration.ofSeconds(3));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/push/sub-1");
    }

    @Test
    @DisplayName("규격에 맞는 헤더(VAPID, aes128gcm, TTL, Urgency, Topic)와 암호화된 본문을 그대로 보낸다")
    void sendsExpectedRequest() {
        byte[] body = {1, 2, 3, 4};

        WebPushClient.Result result = client.send(endpoint(), body, "comment:pin-1", Duration.ofHours(24), true);

        assertThat(result.outcome()).isEqualTo(WebPushClient.Outcome.DELIVERED);
        assertThat(seenBody.get()).isEqualTo(body);
        var headers = seenHeaders.get();
        assertThat(headers.getFirst("Authorization")).startsWith("vapid t=").endsWith(", k=" + keys.publicKey);
        assertThat(headers.getFirst("Content-encoding")).isEqualTo("aes128gcm");
        assertThat(headers.getFirst("Content-type")).isEqualTo("application/octet-stream");
        assertThat(headers.getFirst("Ttl")).isEqualTo("86400");
        assertThat(headers.getFirst("Urgency")).isEqualTo("high");
        assertThat(headers.getFirst("Topic")).hasSize(32).matches("[A-Za-z0-9_-]+");
    }

    @Test
    @DisplayName("같은 태그는 같은 주제 이름, 다른 태그는 다른 이름이다(대기 중인 알림을 합치는 기준)")
    void topicIsStablePerTag() {
        assertThat(WebPushClient.topicName("comment:abc")).isEqualTo(WebPushClient.topicName("comment:abc"));
        assertThat(WebPushClient.topicName("comment:abc")).isNotEqualTo(WebPushClient.topicName("comment:abd"));
    }

    @ParameterizedTest(name = "응답 {0} → {1}")
    @CsvSource({"201,DELIVERED", "200,DELIVERED", "404,GONE", "410,GONE", "400,REJECTED", "401,REJECTED", "403,REJECTED", "413,REJECTED", "429,RETRYABLE", "500,RETRYABLE", "503,RETRYABLE"})
    void mapsResponseStatus(int status, WebPushClient.Outcome expected) {
        responseStatus.set(status);
        assertThat(client.send(endpoint(), new byte[] {1}, "t", Duration.ofHours(1), false).outcome()).isEqualTo(expected);
    }

    @Test
    @DisplayName("연결할 수 없으면 던지지 않고 일시적 문제로 돌려준다(구독은 지우지 않는다)")
    void unreachableIsRetryable() {
        server.stop(0);
        WebPushClient.Result result = client.send(endpoint(), new byte[] {1}, "t", Duration.ofHours(1), false);
        assertThat(result.outcome()).isEqualTo(WebPushClient.Outcome.RETRYABLE);
    }
}
