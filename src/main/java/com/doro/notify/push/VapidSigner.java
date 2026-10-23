package com.doro.notify.push;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECPrivateKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/**
 * VAPID(RFC 8292): 이 서버가 푸시를 보내도 되는 서버임을 푸시 서비스에 증명한다. 보낼 때마다 푸시 서비스의 주소(origin)를 대상(aud)으로 하는
 * 짧게 유효한 JWT(ES256)를 만들어 {@code Authorization: vapid t=<jwt>, k=<공개 키>} 로 보낸다.
 * 구독은 특정 VAPID 공개 키에 묶여 있어서, 키가 바뀌면 기존 구독은 더 이상 보낼 수 없다.
 */
public final class VapidSigner {

    /** JWT 유효 시간. 규격은 24시간을 넘지 못하게 한다. */
    static final Duration LIFETIME = Duration.ofHours(12);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final PrivateKey privateKey;
    private final String publicKey;
    private final String subject;
    private final Clock clock;

    private VapidSigner(PrivateKey privateKey, String publicKey, String subject, Clock clock) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.subject = subject;
        this.clock = clock;
    }

    /** 설정이 꺼져 있으면 서명할 수 없는 자리표시자를 돌려준다. 켜져 있는데 키가 잘못됐으면 기동 때 바로 실패한다. */
    public static VapidSigner from(PushProperties properties, Clock clock) {
        if (!properties.enabled()) {
            return new VapidSigner(null, "", "", clock);
        }
        String subject = properties.subject().strip();
        if (!subject.startsWith("mailto:") && !subject.startsWith("https://")) {
            throw new IllegalStateException("notify.push.subject 는 mailto: 또는 https:// 로 시작해야 합니다.");
        }
        try {
            byte[] publicBytes = Base64.getUrlDecoder().decode(properties.vapidPublicKey().strip());
            byte[] privateBytes = Base64.getUrlDecoder().decode(properties.vapidPrivateKey().strip());
            PublicKey verifying = WebPushEncryptor.parsePublicKey(publicBytes);
            PrivateKey signing = KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, privateBytes), WebPushEncryptor.curve()));
            VapidSigner signer = new VapidSigner(signing, B64.encodeToString(publicBytes), subject, clock);
            signer.requireMatchingKeyPair(verifying);
            return signer;
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalStateException("VAPID 키를 읽지 못했습니다. notify.push.vapid-public-key / vapid-private-key 를 확인하세요.", e);
        }
    }

    public boolean enabled() {
        return privateKey != null;
    }

    public String publicKey() {
        return publicKey;
    }

    /** @param audience 푸시 서비스의 origin(예: https://fcm.googleapis.com) */
    public String authorization(String audience) {
        if (!enabled()) {
            throw new IllegalStateException("웹 푸시가 설정되지 않았습니다.");
        }
        long expires = clock.instant().plus(LIFETIME).getEpochSecond();
        String header = B64.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = B64.encodeToString(("{\"aud\":\"" + escape(audience) + "\",\"exp\":" + expires + ",\"sub\":\"" + escape(subject) + "\"}").getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;
        return "vapid t=" + signingInput + "." + B64.encodeToString(sign(signingInput)) + ", k=" + publicKey;
    }

    private byte[] sign(String input) {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(privateKey);
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            return derToRaw(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("VAPID 서명에 실패했습니다.", e);
        }
    }

    /** 설정한 공개 키가 개인 키와 짝인지 서명해 보고 확인한다(키를 따로 바꿔 넣는 실수를 기동 때 잡는다). */
    private void requireMatchingKeyPair(PublicKey verifying) throws GeneralSecurityException {
        byte[] data = "vapid-key-check".getBytes(StandardCharsets.US_ASCII);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey);
        signer.update(data);
        byte[] signed = signer.sign();
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(verifying);
        verifier.update(data);
        if (!verifier.verify(signed)) {
            throw new IllegalStateException("VAPID 공개 키와 개인 키가 서로 짝이 아닙니다.");
        }
    }

    /** DER(SEQUENCE { INTEGER r, INTEGER s }) 서명을 JWS 가 쓰는 r||s(각 32바이트) 로 바꾼다. */
    static byte[] derToRaw(byte[] der) {
        int offset = 2;
        if ((der[1] & 0x80) != 0) {
            offset = 2 + (der[1] & 0x7f);
        }
        byte[] raw = new byte[64];
        for (int part = 0; part < 2; part++) {
            int length = der[offset + 1] & 0xff;
            int start = offset + 2;
            if (der[start] == 0 && length > 32) {
                start++;
                length--;
            }
            System.arraycopy(der, start, raw, part * 32 + (32 - length), length);
            offset = start + length;
        }
        return raw;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
