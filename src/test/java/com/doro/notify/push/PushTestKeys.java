package com.doro.notify.push;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/** 시험용 VAPID 키 쌍. 시험마다 새로 만든다(저장소에 키를 두지 않는다). */
public final class PushTestKeys {

    public final String publicKey;
    public final String privateKey;

    private PushTestKeys(String publicKey, String privateKey) {
        this.publicKey = publicKey;
        this.privateKey = privateKey;
    }

    public static PushTestKeys generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            return new PushTestKeys(
                    b64.encodeToString(WebPushEncryptor.encodePublicKey((ECPublicKey) pair.getPublic())),
                    b64.encodeToString(WebPushEncryptor.fixed32(((ECPrivateKey) pair.getPrivate()).getS())));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    PushProperties properties(String... allowedHosts) {
        return new PushProperties(publicKey, privateKey, "mailto:ops@example.test", List.of(allowedHosts), true,
                Duration.ofSeconds(5), 10, 5, 2);
    }
}
