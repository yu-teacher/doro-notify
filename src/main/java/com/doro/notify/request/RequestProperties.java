package com.doro.notify.request;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 알림 요청의 상한(notify.request.*). 넘으면 400 이다.
 *
 * @param maxRecipients 요청 하나의 수신자 수
 * @param defaultTtl    ttlSeconds 를 안 주면 쓰는 값(푸시 서비스가 꺼진 기기를 위해 알림을 보관하는 기간)
 * @param maxTtl        ttlSeconds 의 상한
 * @param titleMax      제목 글자 수
 * @param bodyMax       본문 글자 수
 * @param tagMax        tag 글자 수
 * @param urlMax        url·icon 글자 수
 */
@ConfigurationProperties(prefix = "notify.request")
public record RequestProperties(int maxRecipients, Duration defaultTtl, Duration maxTtl, int titleMax, int bodyMax, int tagMax, int urlMax) {
}
