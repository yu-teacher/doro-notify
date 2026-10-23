package com.doro.notify.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;

/**
 * 웹 푸시 메시지 암호화(RFC 8291, aes128gcm 콘텐츠 인코딩). 브라우저가 준 구독의 공개 키(p256dh)와 인증 비밀(auth)로 메시지를 암호화해
 * 푸시 서비스(FCM 등)가 내용을 볼 수 없게 한다. JDK 암호 API 만 쓰며, RFC 8291 부록 A 의 공식 테스트 벡터로 검증한다.
 *
 * <p>메시지는 레코드 하나로 보낸다(레코드 크기 4096). 본문은 {@link #MAX_PLAINTEXT_BYTES} 바이트를 넘을 수 없다.
 */
public final class WebPushEncryptor {

    static final int RECORD_SIZE = 4096;
    static final int PUBLIC_KEY_BYTES = 65;
    static final int AUTH_SECRET_BYTES = 16;
    private static final int SALT_BYTES = 16;
    private static final int TAG_BITS = 128;
    private static final int HEADER_BYTES = SALT_BYTES + 4 + 1 + PUBLIC_KEY_BYTES;
    /** 푸시 서비스는 요청 본문을 4096바이트까지만 받는다: 헤더(86)와 한 레코드(구분 바이트 1 + 인증 태그 16)를 뺀 값. */
    public static final int MAX_PLAINTEXT_BYTES = 4096 - HEADER_BYTES - 1 - 16;
    private static final byte LAST_RECORD_DELIMITER = 0x02;

    private static final ECParameterSpec P256 = p256();
    private static final BigInteger FIELD_P = new BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);
    private static final BigInteger CURVE_B = new BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16);

    private final SecureRandom random = new SecureRandom();

    /** 브라우저가 준 구독 정보로 메시지를 암호화한다. 돌려주는 값이 푸시 서비스에 보낼 요청 본문 전체다. */
    public byte[] encrypt(byte[] userAgentPublicKey, byte[] authSecret, byte[] plaintext) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), random);
            byte[] salt = new byte[SALT_BYTES];
            random.nextBytes(salt);
            return encrypt(userAgentPublicKey, authSecret, plaintext, generator.generateKeyPair(), salt);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("웹 푸시 암호화를 준비하지 못했습니다.", e);
        }
    }

    /** 서버 임시 키와 솔트를 직접 정하는 형태(RFC 테스트 벡터 검증용). */
    byte[] encrypt(byte[] userAgentPublicKey, byte[] authSecret, byte[] plaintext, KeyPair applicationServerKeys, byte[] salt) {
        if (authSecret == null || authSecret.length != AUTH_SECRET_BYTES) {
            throw new IllegalArgumentException("auth 비밀은 16바이트여야 합니다.");
        }
        if (plaintext.length > MAX_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException("푸시 메시지가 너무 큽니다(최대 " + MAX_PLAINTEXT_BYTES + "바이트).");
        }
        try {
            PublicKey uaPublic = parsePublicKey(userAgentPublicKey);
            byte[] asPublic = encodePublicKey((ECPublicKey) applicationServerKeys.getPublic());

            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(applicationServerKeys.getPrivate());
            agreement.doPhase(uaPublic, true);
            byte[] ecdhSecret = agreement.generateSecret();

            byte[] prkKey = hmac(authSecret, ecdhSecret);
            byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), userAgentPublicKey, asPublic);
            byte[] ikm = hmac(prkKey, concat(keyInfo, new byte[] {1}));

            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})), 12);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(concat(plaintext, new byte[] {LAST_RECORD_DELIMITER}));

            ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER_BYTES + ciphertext.length);
            out.writeBytes(salt);
            out.writeBytes(ByteBuffer.allocate(4).putInt(RECORD_SIZE).array());
            out.write(PUBLIC_KEY_BYTES);
            out.writeBytes(asPublic);
            out.writeBytes(ciphertext);
            return out.toByteArray();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("웹 푸시 암호화에 실패했습니다.", e);
        }
    }

    /** 구독 키(p256dh: 곡선 위의 점, auth: 16바이트)가 올바른 형식인지 확인한다. 틀리면 IllegalArgumentException. */
    public static void validateSubscriptionKeys(String p256dh, String auth) {
        parsePublicKey(java.util.Base64.getUrlDecoder().decode(p256dh));
        if (java.util.Base64.getUrlDecoder().decode(auth).length != AUTH_SECRET_BYTES) {
            throw new IllegalArgumentException("auth 비밀은 16바이트여야 합니다.");
        }
    }

    /**
     * 비압축 P-256 공개 키(0x04 || X || Y, 65바이트)를 읽는다. 사용자가 보낸 값이므로 곡선 위의 점인지 직접 확인한다
     * (곡선 밖의 점으로 비밀 값을 캐내려는 공격 방지).
     */
    static PublicKey parsePublicKey(byte[] encoded) {
        if (encoded == null || encoded.length != PUBLIC_KEY_BYTES || encoded[0] != 0x04) {
            throw new IllegalArgumentException("공개 키 형식이 올바르지 않습니다.");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(encoded, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(encoded, 33, 65));
        if (!isOnCurve(x, y)) {
            throw new IllegalArgumentException("공개 키가 곡선 위의 점이 아닙니다.");
        }
        try {
            return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("공개 키를 읽지 못했습니다.", e);
        }
    }

    static byte[] encodePublicKey(ECPublicKey key) {
        byte[] encoded = new byte[PUBLIC_KEY_BYTES];
        encoded[0] = 0x04;
        System.arraycopy(fixed32(key.getW().getAffineX()), 0, encoded, 1, 32);
        System.arraycopy(fixed32(key.getW().getAffineY()), 0, encoded, 33, 32);
        return encoded;
    }

    static ECParameterSpec curve() {
        return P256;
    }

    static byte[] fixed32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    private static boolean isOnCurve(BigInteger x, BigInteger y) {
        if (x.signum() < 0 || y.signum() < 0 || x.compareTo(FIELD_P) >= 0 || y.compareTo(FIELD_P) >= 0) {
            return false;
        }
        // y^2 = x^3 - 3x + b (mod p)
        BigInteger left = y.multiply(y).mod(FIELD_P);
        BigInteger right = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(CURVE_B).mod(FIELD_P);
        return left.equals(right);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
