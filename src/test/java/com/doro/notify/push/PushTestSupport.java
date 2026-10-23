package com.doro.notify.push;

import com.sun.net.httpserver.HttpServer;
import com.jayway.jsonpath.JsonPath;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 푸시 시험 도구: 가짜 푸시 서비스(요청을 기록하고 정해 둔 상태를 돌려준다)와, 브라우저처럼 키를 들고 받은 메시지를 직접 복호화하는 기기.
 * 푸시 설정(VAPID 키, 허용 도메인)은 {@code AbstractNotifyTest} 가 주입한다.
 */
public final class PushTestSupport {

    public static final PushTestKeys KEYS = PushTestKeys.generate();
    public static final FakePushService SERVICE = FakePushService.start();

    private PushTestSupport() {
    }

    /** 푸시 서비스가 받은 요청 하나. */
    public record Received(String path, Map<String, String> headers, byte[] body) {
    }

    public static final class FakePushService {
        private final HttpServer server;
        private final List<Received> received = new CopyOnWriteArrayList<>();
        private final Map<String, Integer> statusByName = new ConcurrentHashMap<>();

        private FakePushService(HttpServer server) {
            this.server = server;
        }

        static FakePushService start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
                FakePushService service = new FakePushService(server);
                server.createContext("/push/", exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    Map<String, String> headers = new ConcurrentHashMap<>();
                    exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.get(0)));
                    service.received.add(new Received(path, headers, exchange.getRequestBody().readAllBytes()));
                    String name = path.substring("/push/".length());
                    exchange.sendResponseHeaders(service.statusByName.getOrDefault(name, 201), -1);
                    exchange.close();
                });
                server.start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
                return service;
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }

        public String endpoint(String name) {
            return "http://localhost:" + server.getAddress().getPort() + "/push/" + name;
        }

        public void respondWith(String name, int status) {
            statusByName.put(name, status);
        }

        /** 시험 사이에 받은 요청과 정해 둔 응답을 비운다. */
        public void reset() {
            received.clear();
            statusByName.clear();
        }

        public List<Received> receivedBy(String name) {
            return received.stream().filter(r -> r.path().equals("/push/" + name)).toList();
        }
    }

    /** 브라우저 역할: 구독 키를 만들고, 서버가 보낸 메시지를 복호화한다. */
    public static final class TestDevice {
        public final String name = UUID.randomUUID().toString();
        public final String endpoint = SERVICE.endpoint(name);
        private final KeyPair keys;
        private final byte[] authSecret = new byte[16];

        public TestDevice() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
                generator.initialize(new ECGenParameterSpec("secp256r1"));
                keys = generator.generateKeyPair();
                new java.security.SecureRandom().nextBytes(authSecret);
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        /** 브라우저의 PushSubscription.toJSON() 과 같은 모양. */
        public String subscriptionJson() {
            Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            return "{\"endpoint\":\"" + endpoint + "\",\"keys\":{\"p256dh\":\"" + b64.encodeToString(WebPushEncryptor.encodePublicKey((ECPublicKey) keys.getPublic()))
                    + "\",\"auth\":\"" + b64.encodeToString(authSecret) + "\"}}";
        }

        public List<Received> received() {
            return SERVICE.receivedBy(name);
        }

        /** 받은 n번째 알림의 JSON 본문(복호화). */
        public String message(int index) {
            return new String(decrypt(received().get(index).body(), keys, authSecret), StandardCharsets.UTF_8);
        }

        public String field(int index, String field) {
            return JsonPath.read(message(index), "$." + field);
        }

        public String lastField(String field) {
            return field(received().size() - 1, field);
        }
    }

    /** RFC 8291 5절의 받는 쪽 절차(복호화). 보내는 쪽 구현과 따로 작성해 서로를 검증한다. */
    public static byte[] decrypt(byte[] body, KeyPair receiver, byte[] authSecret) {
        try {
            byte[] salt = Arrays.copyOfRange(body, 0, 16);
            int idLength = body[20] & 0xff;
            byte[] asPublic = Arrays.copyOfRange(body, 21, 21 + idLength);
            byte[] ciphertext = Arrays.copyOfRange(body, 21 + idLength, body.length);
            if (ByteBuffer.wrap(body, 16, 4).getInt() != 4096) {
                throw new IllegalArgumentException("record size");
            }
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(receiver.getPrivate());
            agreement.doPhase(WebPushEncryptor.parsePublicKey(asPublic), true);
            byte[] ecdh = agreement.generateSecret();
            byte[] uaPublic = WebPushEncryptor.encodePublicKey((ECPublicKey) receiver.getPublic());

            byte[] prkKey = hmac(authSecret, ecdh);
            ByteArrayOutputStream info = new ByteArrayOutputStream();
            info.writeBytes("WebPush: info\0".getBytes(StandardCharsets.US_ASCII));
            info.writeBytes(uaPublic);
            info.writeBytes(asPublic);
            info.write(1);
            byte[] prk = hmac(salt, hmac(prkKey, info.toByteArray()));
            byte[] cek = Arrays.copyOf(hmac(prk, withOne("Content-Encoding: aes128gcm\0")), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, withOne("Content-Encoding: nonce\0")), 12);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] padded = cipher.doFinal(ciphertext);
            if (padded[padded.length - 1] != 0x02) {
                throw new IllegalArgumentException("padding delimiter");
            }
            return Arrays.copyOf(padded, padded.length - 1);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] withOne(String label) {
        byte[] text = label.getBytes(StandardCharsets.US_ASCII);
        byte[] out = Arrays.copyOf(text, text.length + 1);
        out[text.length] = 1;
        return out;
    }

    private static byte[] hmac(byte[] key, byte[] data) throws java.security.GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }
}
