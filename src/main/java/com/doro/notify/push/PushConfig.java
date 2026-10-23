package com.doro.notify.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Clock;

@Configuration
public class PushConfig {

    private static final Logger log = LoggerFactory.getLogger(PushConfig.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    VapidSigner vapidSigner(PushProperties properties, Clock clock) {
        VapidSigner signer = VapidSigner.from(properties, clock);
        log.info("Web push {}", signer.enabled() ? "enabled" : "disabled (VAPID keys not configured)");
        return signer;
    }

    @Bean
    WebPushEncryptor webPushEncryptor() {
        return new WebPushEncryptor();
    }

    /** 리디렉션은 따라가지 않는다(허용 목록 밖으로 새지 않게). */
    @Bean
    WebPushClient webPushClient(VapidSigner signer, PushProperties properties) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new WebPushClient(http, signer, properties.requestTimeout());
    }
}
