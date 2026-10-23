package com.doro.notify.support;

import io.jsonwebtoken.Jwts;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Date;
import java.util.UUID;

/**
 * 가짜 Doro IAM: 시험용 RS256 액세스 토큰을 발급한다. 클레임은 SDK `DoroTokenVerifier` 가 읽는 것에 맞췄다
 * (sub=사용자 id, email, sid=세션 id, role, cid=OAuth 클라이언트, exp 필수). 공개 키는 `JwksKeyProvider.registerKey` 로 등록한다.
 * 진짜 IAM 과의 연동(PKCE·redirect_uri)은 이 환경에서 시험할 수 없다(doro-interview 와 같은 방식).
 */
public final class FakeDoro {

    public static final String KID = "fake-doro-kid";
    public static final String CLIENT_ID = "doro-party";

    private final KeyPair keyPair = newKeyPair();
    private final KeyPair otherKeyPair = newKeyPair();

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public PublicKey publicKey() {
        return keyPair.getPublic();
    }

    private String build(String subject, String clientId, long expiresInMillis, KeyPair signWith) {
        long now = System.currentTimeMillis();
        var builder = Jwts.builder()
                .header().keyId(KID).and()
                .claim("email", "user@example.test")
                .claim("sid", UUID.randomUUID().toString())
                .claim("role", "USER")
                .issuedAt(new Date(now - 10_000))
                .expiration(new Date(now + expiresInMillis));
        if (subject != null) {
            builder.subject(subject);
        }
        if (clientId != null) {
            builder.claim("cid", clientId);
        }
        return builder.signWith(signWith.getPrivate(), Jwts.SIG.RS256).compact();
    }

    /** 알림을 쓰는 앱(BFF 클라이언트 cid=doro-party)가 받은 정상 토큰(15분). */
    public String token(UUID userId) {
        return build(userId.toString(), CLIENT_ID, 900_000, keyPair);
    }

    /** OAuth 클라이언트 없이 Doro 가 직접 발급한 사용자 토큰(cid 없음). */
    public String tokenWithoutClient(UUID userId) {
        return build(userId.toString(), null, 900_000, keyPair);
    }

    /** 다른 OAuth 클라이언트(허용 목록에 없는 앱)가 받은 토큰. */
    public String tokenForClient(UUID userId, String clientId) {
        return build(userId.toString(), clientId, 900_000, keyPair);
    }

    /** 서명은 유효하지만 이미 만료됐다. */
    public String expired(UUID userId) {
        return build(userId.toString(), CLIENT_ID, -3_600_000, keyPair);
    }

    /** kid 는 같지만 다른 키로 서명했다(위조). */
    public String forged(UUID userId) {
        return build(userId.toString(), CLIENT_ID, 900_000, otherKeyPair);
    }

    /** 서명은 유효하지만 sub 가 없다(사용자를 알 수 없다). */
    public String withoutSubject() {
        return build(null, CLIENT_ID, 900_000, keyPair);
    }
}
