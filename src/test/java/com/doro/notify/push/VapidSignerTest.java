package com.doro.notify.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VapidSignerTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);
    private static final Pattern HEADER = Pattern.compile("^vapid t=([\\w-]+)\\.([\\w-]+)\\.([\\w-]+), k=([\\w-]+)$");

    private static String json(String part) {
        return new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Authorization 헤더는 vapid t=<JWT>, k=<공개 키> 형식이고 JWT 에 aud·exp(12시간)·sub 가 들어 있다")
    void headerAndClaims() {
        PushTestKeys keys = PushTestKeys.generate();
        String header = VapidSigner.from(keys.properties(), NOW).authorization("https://fcm.googleapis.com");

        Matcher matcher = HEADER.matcher(header);
        assertThat(matcher.matches()).isTrue();
        assertThat(json(matcher.group(1))).isEqualTo("{\"typ\":\"JWT\",\"alg\":\"ES256\"}");
        String expires = String.valueOf(NOW.instant().plusSeconds(12 * 3600).getEpochSecond());
        assertThat(json(matcher.group(2))).isEqualTo("{\"aud\":\"https://fcm.googleapis.com\",\"exp\":" + expires + ",\"sub\":\"mailto:ops@example.test\"}");
        assertThat(matcher.group(4)).isEqualTo(keys.publicKey);
    }

    @Test
    @DisplayName("JWT 서명(r||s 64바이트)은 공개 키로 JDK 가 독립적으로 검증된다")
    void signatureVerifiesWithPublicKey() throws Exception {
        PushTestKeys keys = PushTestKeys.generate();
        VapidSigner signer = VapidSigner.from(keys.properties(), NOW);
        for (int i = 0; i < 20; i++) {  // r, s 길이가 달라지는 경우(앞자리 0, 최상위 비트)를 여러 번 겪는다
            Matcher matcher = HEADER.matcher(signer.authorization("https://push.example"));
            assertThat(matcher.matches()).isTrue();
            byte[] raw = Base64.getUrlDecoder().decode(matcher.group(3));
            assertThat(raw).hasSize(64);

            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(WebPushEncryptor.parsePublicKey(Base64.getUrlDecoder().decode(keys.publicKey)));
            verifier.update((matcher.group(1) + "." + matcher.group(2)).getBytes(StandardCharsets.US_ASCII));
            assertThat(verifier.verify(rawToDer(raw))).isTrue();
        }
    }

    @Test
    @DisplayName("설정이 비어 있으면 꺼진 상태이고, 서명하려 하면 막힌다")
    void disabledWhenNotConfigured() {
        PushProperties blank = new PushProperties("", "", "", java.util.List.of(), true, java.time.Duration.ofSeconds(5), 10, 5, 2);
        VapidSigner signer = VapidSigner.from(blank, NOW);
        assertThat(signer.enabled()).isFalse();
        assertThatThrownBy(() -> signer.authorization("https://x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("공개 키와 개인 키가 짝이 아니면 기동 때 실패한다")
    void rejectsMismatchedKeyPair() {
        PushTestKeys a = PushTestKeys.generate();
        PushTestKeys b = PushTestKeys.generate();
        PushProperties mismatched = new PushProperties(a.publicKey, b.privateKey, "mailto:ops@example.test", java.util.List.of(), true, java.time.Duration.ofSeconds(5), 10, 5, 2);
        assertThatThrownBy(() -> VapidSigner.from(mismatched, NOW)).isInstanceOf(IllegalStateException.class).hasMessageContaining("짝이 아닙니다");
    }

    @Test
    @DisplayName("연락처(sub)는 mailto: 나 https:// 만, 깨진 키는 기동 때 실패한다")
    void rejectsBadSubjectAndBadKeys() {
        PushTestKeys keys = PushTestKeys.generate();
        PushProperties badSubject = new PushProperties(keys.publicKey, keys.privateKey, "ops@example.test", java.util.List.of(), true, java.time.Duration.ofSeconds(5), 10, 5, 2);
        assertThatThrownBy(() -> VapidSigner.from(badSubject, NOW)).isInstanceOf(IllegalStateException.class).hasMessageContaining("subject");
        PushProperties badKey = new PushProperties("not-a-key", keys.privateKey, "mailto:ops@example.test", java.util.List.of(), true, java.time.Duration.ofSeconds(5), 10, 5, 2);
        assertThatThrownBy(() -> VapidSigner.from(badKey, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("DER → r||s 변환: 앞에 0 이 붙은 33바이트 정수와 짧은 정수를 32바이트로 맞춘다")
    void derConversion() {
        byte[] r = new byte[33];
        r[0] = 0;
        java.util.Arrays.fill(r, 1, 33, (byte) 0xff);
        byte[] s = new byte[] {0x01, 0x02};
        byte[] der = new byte[2 + 2 + r.length + 2 + s.length];
        der[0] = 0x30;
        der[1] = (byte) (2 + r.length + 2 + s.length);
        der[2] = 0x02;
        der[3] = (byte) r.length;
        System.arraycopy(r, 0, der, 4, r.length);
        der[4 + r.length] = 0x02;
        der[5 + r.length] = (byte) s.length;
        System.arraycopy(s, 0, der, 6 + r.length, s.length);

        byte[] raw = VapidSigner.derToRaw(der);

        assertThat(raw).hasSize(64);
        assertThat(java.util.Arrays.copyOfRange(raw, 0, 32)).containsOnly((byte) 0xff);
        assertThat(java.util.Arrays.copyOfRange(raw, 32, 64)).endsWith((byte) 0x01, (byte) 0x02).startsWith((byte) 0x00);
    }

    private static byte[] rawToDer(byte[] raw) {
        byte[] r = new BigInteger(1, java.util.Arrays.copyOfRange(raw, 0, 32)).toByteArray();
        byte[] s = new BigInteger(1, java.util.Arrays.copyOfRange(raw, 32, 64)).toByteArray();
        byte[] der = new byte[6 + r.length + s.length];
        der[0] = 0x30;
        der[1] = (byte) (4 + r.length + s.length);
        der[2] = 0x02;
        der[3] = (byte) r.length;
        System.arraycopy(r, 0, der, 4, r.length);
        der[4 + r.length] = 0x02;
        der[5 + r.length] = (byte) s.length;
        System.arraycopy(s, 0, der, 6 + r.length, s.length);
        return der;
    }
}
