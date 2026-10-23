package com.doro.notify.delivery;

import java.nio.charset.StandardCharsets;

/**
 * 기기로 가는 푸시 메시지의 계약 v1(설계 7.3). 앱별 서비스 워커는 이 모양만 알면 된다.
 *
 * <pre>{ "v": 1, "app": "party", "type": "comment", "title": "…", "body": "…", "tag": "…", "url": "/party/?…", "icon": "…" }</pre>
 *
 * <p>필드 순서는 고정이다. {@code tag}·{@code icon} 은 없으면 키 자체를 싣지 않는다(서비스 워커는 없는 키를 없는 값으로 다룬다).
 * 알 수 없는 {@code v} 를 받은 서비스 워커는 무시하지 않고 title/body 만으로 표시한다는 것이 계약이므로, 앞으로도 title·body 는 모든 버전에 남긴다.
 * 클라이언트 쪽 복사본이 어긋나는 것을 잡으려고 이 형식은 계약 테스트({@code PayloadContractTest})가 고정한다.
 */
public final class PayloadV1 {

    public static final int VERSION = 1;

    private PayloadV1() {
    }

    public static byte[] build(String app, String type, String title, String body, String tag, String url, String icon) {
        StringBuilder json = new StringBuilder(256);
        json.append("{\"v\":").append(VERSION)
                .append(",\"app\":").append(quote(app))
                .append(",\"type\":").append(quote(type))
                .append(",\"title\":").append(quote(title))
                .append(",\"body\":").append(quote(body));
        if (tag != null && !tag.isEmpty()) {
            json.append(",\"tag\":").append(quote(tag));
        }
        json.append(",\"url\":").append(quote(url));
        if (icon != null && !icon.isEmpty()) {
            json.append(",\"icon\":").append(quote(icon));
        }
        return json.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }

    static String quote(String value) {
        String text = value == null ? "" : value;
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                // U+2028·2029 는 JSON 에서는 유효하지만 JS 문자열 리터럴로 옮겨지면 깨지므로 이스케이프한다
                case ' ' -> out.append("\\u2028");
                case ' ' -> out.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
