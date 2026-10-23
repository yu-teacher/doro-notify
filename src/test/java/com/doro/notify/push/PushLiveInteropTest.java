package com.doro.notify.push;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 푸시 서비스(FCM 등)와의 호환 확인. 일반 시험에서는 건너뛴다(PUSH_LIVE_DIR 가 있을 때만 실행).
 *
 * <p>준비: 실제 브라우저에서 만든 구독({@code subscription.json}: endpoint, keys.p256dh, keys.auth)과 그 구독을 만들 때 쓴 VAPID 키
 * ({@code vapid.json}: publicKey, privateKey)를 한 폴더에 둔다. 이 시험이 그 구독으로 실제 메시지를 보내고, 푸시 서비스가 받아들이는지(2xx) 확인한다.
 * 브라우저가 그 메시지를 복호화해 알림을 띄우는지는 브라우저 쪽에서 확인한다.
 *
 * <pre>PUSH_LIVE_DIR=/tmp/live PUSH_LIVE_MESSAGE='{"title":"..."}' ./gradlew test --tests '*PushLiveInteropTest'</pre>
 */
@EnabledIfEnvironmentVariable(named = "PUSH_LIVE_DIR", matches = ".+")
class PushLiveInteropTest {

    @Test
    @DisplayName("실제 구독으로 보낸 메시지를 푸시 서비스가 받아들인다(암호화·VAPID 서명·헤더가 규격에 맞는다)")
    void realPushServiceAcceptsOurMessage() throws Exception {
        Path dir = Path.of(System.getenv("PUSH_LIVE_DIR"));
        String subscription = Files.readString(dir.resolve("subscription.json"));
        String vapid = Files.readString(dir.resolve("vapid.json"));
        String message = System.getenv().getOrDefault("PUSH_LIVE_MESSAGE", "{\"v\":1,\"app\":\"party\",\"type\":\"test\",\"title\":\"연동 확인\",\"body\":\"서버에서 보낸 실제 푸시\",\"tag\":\"live\",\"url\":\"/party/\"}");

        PushProperties properties = new PushProperties(JsonPath.read(vapid, "$.publicKey"), JsonPath.read(vapid, "$.privateKey"), "mailto:interop@example.test",
                List.of("fcm.googleapis.com", "updates.push.services.mozilla.com", "web.push.apple.com", "notify.windows.com"), true,
                Duration.ofSeconds(15), 10, 5, 2);
        URI endpoint = new PushEndpointPolicy(properties).validate(JsonPath.read(subscription, "$.endpoint"));
        byte[] body = new WebPushEncryptor().encrypt(
                Base64.getUrlDecoder().decode(JsonPath.<String>read(subscription, "$.keys.p256dh")),
                Base64.getUrlDecoder().decode(JsonPath.<String>read(subscription, "$.keys.auth")),
                message.getBytes(StandardCharsets.UTF_8));
        WebPushClient client = new WebPushClient(HttpClient.newHttpClient(), VapidSigner.from(properties, Clock.systemUTC()), Duration.ofSeconds(15));

        WebPushClient.Result result = client.send(endpoint, body, "live", Duration.ofHours(1), true);

        System.out.println("PUSH_LIVE host=" + endpoint.getHost() + " status=" + result.status() + " outcome=" + result.outcome());
        assertThat(result.outcome()).isEqualTo(WebPushClient.Outcome.DELIVERED);
    }
}
