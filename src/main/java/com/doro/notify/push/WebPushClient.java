package com.doro.notify.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;

/** 암호화한 메시지를 푸시 서비스(FCM, Mozilla, Apple 등)에 전달한다. 결과만 돌려주고, 구독 정보 정리는 호출한 쪽이 한다. */
public class WebPushClient {

    private static final Logger log = LoggerFactory.getLogger(WebPushClient.class);

    /** 푸시 서비스 응답을 우리가 해야 할 일로 나눈 것. */
    public enum Outcome {
        /** 전달됐다 */
        DELIVERED,
        /** 구독이 없어졌다(404/410): 지운다 */
        GONE,
        /** 거절됐다(키 불일치·형식 오류 등): 같은 요청을 다시 보내도 소용없다. 반복되면 지운다 */
        REJECTED,
        /** 일시적 문제(시간 초과, 429, 5xx): 구독은 그대로 둔다 */
        RETRYABLE
    }

    public record Result(Outcome outcome, int status) {
    }

    private final HttpClient http;
    private final VapidSigner signer;
    private final Duration requestTimeout;

    public WebPushClient(HttpClient http, VapidSigner signer, Duration requestTimeout) {
        this.http = http;
        this.signer = signer;
        this.requestTimeout = requestTimeout;
    }

    /**
     * @param topic 같은 주제의 대기 중인 알림을 하나로 합치는 이름(32자 이하 URL 안전 문자). 기기가 꺼져 있는 동안 쌓이지 않게 한다.
     */
    public Result send(URI endpoint, byte[] encryptedBody, String topic, Duration ttl, boolean urgent) {
        String audience = endpoint.getScheme() + "://" + endpoint.getAuthority();
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Authorization", signer.authorization(audience))
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("TTL", Long.toString(ttl.toSeconds()))
                .header("Urgency", urgent ? "high" : "normal")
                .header("Topic", topicName(topic))
                .POST(HttpRequest.BodyPublishers.ofByteArray(encryptedBody))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            Result result = new Result(classify(response.statusCode()), response.statusCode());
            if (result.outcome() != Outcome.DELIVERED && result.outcome() != Outcome.GONE) {
                // 구독 주소와 키는 비밀이므로 로그에는 푸시 서비스의 호스트와 상태 코드만 남긴다
                log.warn("Web push not delivered: host={}, status={}, outcome={}", endpoint.getHost(), result.status(), result.outcome());
            }
            return result;
        } catch (IOException e) {
            log.warn("Web push request failed: host={}, error={}", endpoint.getHost(), e.getClass().getSimpleName());
            return new Result(Outcome.RETRYABLE, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.RETRYABLE, 0);
        }
    }

    static Outcome classify(int status) {
        if (status >= 200 && status < 300) {
            return Outcome.DELIVERED;
        }
        if (status == 404 || status == 410) {
            return Outcome.GONE;
        }
        if (status == 429 || status >= 500) {
            return Outcome.RETRYABLE;
        }
        return Outcome.REJECTED;
    }

    /** 주제 이름은 32자 이하 URL 안전 문자여야 한다. 알림 태그를 해시해 만든다. */
    static String topicName(String tag) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(tag.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
