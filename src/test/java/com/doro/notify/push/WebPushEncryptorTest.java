package com.doro.notify.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RFC 8291 부록 A 의 공식 테스트 벡터로 웹 푸시 암호화를 검증한다. */
class WebPushEncryptorTest {

    // RFC 8291 Appendix A
    private static final String PLAINTEXT = "V2hlbiBJIGdyb3cgdXAsIEkgd2FudCB0byBiZSBhIHdhdGVybWVsb24";
    private static final String AS_PUBLIC = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String HEADER = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String CIPHERTEXT = "8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ";

    private static byte[] b64(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private static KeyPair keyPair(String privateKey, String publicKey) throws Exception {
        PrivateKey priv = java.security.KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, b64(privateKey)), WebPushEncryptor.curve()));
        return new KeyPair(WebPushEncryptor.parsePublicKey(b64(publicKey)), priv);
    }

    @Test
    @DisplayName("RFC 8291 부록 A 의 입력으로 암호화하면 공식 예시의 헤더와 암호문이 바이트 단위로 같다")
    void matchesRfcVector() throws Exception {
        byte[] body = new WebPushEncryptor().encrypt(b64(UA_PUBLIC), b64(AUTH_SECRET), b64(PLAINTEXT), keyPair(AS_PRIVATE, AS_PUBLIC), b64(SALT));

        // 헤더(86바이트)와 암호문은 RFC 에 따로 인코딩돼 있다. 문자열로 이으면 Base64 정렬이 달라지므로 바이트로 비교한다.
        assertThat(Arrays.copyOfRange(body, 0, 86)).isEqualTo(b64(HEADER));
        assertThat(Arrays.copyOfRange(body, 86, body.length)).isEqualTo(b64(CIPHERTEXT));
        assertThat(body).hasSize(b64(HEADER).length + b64(CIPHERTEXT).length);
    }

    @Test
    @DisplayName("받는 쪽(RFC 8291 복호화)이 임의의 키와 솔트로 암호화한 메시지를 풀어 원문을 얻는다")
    void roundTripsWithRandomKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair receiver = generator.generateKeyPair();
        byte[] auth = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);
        byte[] message = "{\"title\":\"새 댓글\",\"body\":\"한글과 emoji 😀\"}".getBytes(StandardCharsets.UTF_8);

        byte[] body = new WebPushEncryptor().encrypt(WebPushEncryptor.encodePublicKey((ECPublicKey) receiver.getPublic()), auth, message);

        assertThat(PushTestSupport.decrypt(body, receiver, auth)).isEqualTo(message);
    }

    @Test
    @DisplayName("같은 메시지도 매번 솔트와 서버 키가 달라 암호문이 달라진다")
    void freshKeysEachTime() throws Exception {
        byte[] first = new WebPushEncryptor().encrypt(b64(UA_PUBLIC), b64(AUTH_SECRET), b64(PLAINTEXT));
        byte[] second = new WebPushEncryptor().encrypt(b64(UA_PUBLIC), b64(AUTH_SECRET), b64(PLAINTEXT));
        assertThat(first).isNotEqualTo(second);
        assertThat(Arrays.copyOfRange(first, 0, 16)).isNotEqualTo(Arrays.copyOfRange(second, 0, 16));
    }

    @Test
    @DisplayName("곡선 위의 점이 아닌 공개 키는 거절한다(잘못된 곡선 공격 방지)")
    void rejectsPointOffTheCurve() {
        byte[] offCurve = b64(UA_PUBLIC).clone();
        offCurve[64] ^= 0x01;
        assertThatThrownBy(() -> new WebPushEncryptor().encrypt(offCurve, b64(AUTH_SECRET), b64(PLAINTEXT)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("곡선");
    }

    @Test
    @DisplayName("공개 키 길이·형식, auth 길이가 틀리면 거절한다")
    void rejectsMalformedInputs() {
        WebPushEncryptor encryptor = new WebPushEncryptor();
        assertThatThrownBy(() -> encryptor.encrypt(new byte[64], b64(AUTH_SECRET), b64(PLAINTEXT))).isInstanceOf(IllegalArgumentException.class);
        byte[] compressed = b64(UA_PUBLIC).clone();
        compressed[0] = 0x02;
        assertThatThrownBy(() -> encryptor.encrypt(compressed, b64(AUTH_SECRET), b64(PLAINTEXT))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encryptor.encrypt(b64(UA_PUBLIC), new byte[15], b64(PLAINTEXT))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("auth");
        assertThatThrownBy(() -> encryptor.encrypt(b64(UA_PUBLIC), null, b64(PLAINTEXT))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("푸시 서비스의 4096바이트 한도를 넘는 메시지는 거절하고, 한도 바로 안쪽은 보낼 수 있다")
    void enforcesPayloadLimit() {
        WebPushEncryptor encryptor = new WebPushEncryptor();
        byte[] atLimit = new byte[WebPushEncryptor.MAX_PLAINTEXT_BYTES];
        assertThat(encryptor.encrypt(b64(UA_PUBLIC), b64(AUTH_SECRET), atLimit)).hasSize(4096);
        assertThatThrownBy(() -> encryptor.encrypt(b64(UA_PUBLIC), b64(AUTH_SECRET), new byte[WebPushEncryptor.MAX_PLAINTEXT_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("너무 큽니다");
    }
}
