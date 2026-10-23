package com.doro.notify.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 내부 API 를 호출할 수 있는 서비스의 이름과 토큰 목록. 설정 형식은 {@code 이름:토큰,이름:토큰}(IAM·Guard 의 서비스 토큰과 같은 형식)이다.
 * 이름은 알림 앱 id(party, blog, …)와 같아야 하고, 호출자는 자기 이름의 앱으로만 요청할 수 있다.
 *
 * <p>토큰은 길고 무작위여야 하므로 짧은 값은 기동 때 거부한다. 비교는 시간차 공격을 피하려고 모든 항목을 끝까지 비교한다.
 * 토큰 값은 로그·예외 메시지에 남기지 않는다(호출자 이름만 남긴다).
 */
public final class InternalCallerTokens {

    /** 이보다 짧은 토큰은 추측·대입 공격에 약하다. {@code openssl rand -hex 32} 는 64자다. */
    public static final int MIN_TOKEN_LENGTH = 32;

    private record Entry(String name, byte[] token) {}

    private final List<Entry> entries;

    private InternalCallerTokens(List<Entry> entries) {
        this.entries = entries;
    }

    public static InternalCallerTokens parse(String raw) {
        List<Entry> parsed = new ArrayList<>();
        Set<String> names = new HashSet<>();
        if (raw != null && !raw.isBlank()) {
            for (String item : raw.split(",")) {
                String trimmed = item.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int colon = trimmed.indexOf(':');
                if (colon <= 0 || colon == trimmed.length() - 1) {
                    // 항목 전체를 메시지에 싣지 않는다(토큰이 섞여 있을 수 있다)
                    throw new IllegalStateException("내부 API 토큰은 '이름:토큰' 형식이어야 합니다.");
                }
                String name = trimmed.substring(0, colon).trim();
                String token = trimmed.substring(colon + 1).trim();
                if (token.length() < MIN_TOKEN_LENGTH) {
                    throw new IllegalStateException("내부 API 토큰 '" + name + "' 이(가) 너무 짧습니다(최소 " + MIN_TOKEN_LENGTH + "자).");
                }
                if (!names.add(name)) {
                    throw new IllegalStateException("내부 API 호출자 이름이 중복됩니다: " + name);
                }
                parsed.add(new Entry(name, token.getBytes(StandardCharsets.UTF_8)));
            }
        }
        return new InternalCallerTokens(List.copyOf(parsed));
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    /** 제시된 토큰의 호출자 이름. 없거나 맞지 않으면 비어 있다. 모든 항목을 끝까지 비교한다. */
    public Optional<String> callerOf(String presented) {
        if (presented == null || presented.isEmpty()) {
            return Optional.empty();
        }
        byte[] candidate = presented.getBytes(StandardCharsets.UTF_8);
        String matched = null;
        for (Entry entry : entries) {
            if (MessageDigest.isEqual(entry.token(), candidate)) {
                matched = entry.name();
            }
        }
        return Optional.ofNullable(matched);
    }
}
