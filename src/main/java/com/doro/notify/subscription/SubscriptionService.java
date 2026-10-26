package com.doro.notify.subscription;

import com.doro.notify.common.exception.ErrorCode;
import com.doro.notify.common.exception.NotifyException;
import com.doro.notify.push.PushEndpointPolicy;
import com.doro.notify.push.PushProperties;
import com.doro.notify.push.VapidSigner;
import com.doro.notify.push.WebPushEncryptor;
import com.doro.notify.registry.AppRegistry;
import com.doro.notify.registry.NotifyApp;
import com.doro.notify.subscription.SubscriptionDtos.ConfigResponse;
import com.doro.notify.subscription.SubscriptionDtos.Device;
import com.doro.notify.subscription.SubscriptionDtos.DeviceApp;
import com.doro.notify.subscription.SubscriptionDtos.DevicesResponse;
import com.doro.notify.subscription.SubscriptionDtos.SubscribeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** 기기 구독의 등록·해제·조회. 로그에는 사용자 id 와 구독 id 만 남긴다(endpoint·키는 남기지 않는다). */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    static final int USER_AGENT_MAX = 300;
    static final Pattern DEVICE_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final int KEY_MAX = 128;
    private static final int AUTH_MAX = 64;

    private final PushProperties properties;
    private final VapidSigner signer;
    private final PushEndpointPolicy endpointPolicy;
    private final SubscriptionRepository subscriptions;
    private final AppRegistry registry;
    private final Clock clock;

    public SubscriptionService(PushProperties properties, VapidSigner signer, PushEndpointPolicy endpointPolicy,
                               SubscriptionRepository subscriptions, AppRegistry registry, Clock clock) {
        this.properties = properties;
        this.signer = signer;
        this.endpointPolicy = endpointPolicy;
        this.subscriptions = subscriptions;
        this.registry = registry;
        this.clock = clock;
    }

    public ConfigResponse config() {
        return new ConfigResponse(signer.enabled(), signer.enabled() ? signer.publicKey() : "");
    }

    /**
     * 이 기기(구독)를 내 알림 기기로 등록한다. 주소는 허용된 푸시 서비스 도메인이어야 하고 키는 올바른 형식이어야 한다(서버가 대신 접속하고 이 키로 암호화한다).
     * 같은 endpoint 를 다시 등록하면 갱신이고, 다른 계정이 쓰던 기기를 이 계정으로 등록하면 소유가 옮겨진다.
     */
    @Transactional
    public void subscribe(UUID userId, NotifyApp app, SubscribeRequest request, String userAgent) {
        if (!signer.enabled()) {
            throw new NotifyException(ErrorCode.PUSH_DISABLED);
        }
        if (request == null || request.keys() == null) {
            throw new NotifyException(ErrorCode.INVALID_INPUT);
        }
        String deviceId = request.deviceId();
        if (deviceId == null || !DEVICE_ID.matcher(deviceId).matches()) {
            throw new NotifyException(ErrorCode.INVALID_INPUT, "기기 표식(deviceId)이 올바르지 않습니다.");
        }
        String endpoint = request.endpoint();
        if (endpoint == null || !endpoint.chars().allMatch(c -> c < 0x80)) {
            throw new NotifyException(ErrorCode.INVALID_INPUT, "지원하지 않는 푸시 구독 주소입니다.");
        }
        endpointPolicy.validate(endpoint);
        requireValidKeys(request.keys().p256dh(), request.keys().auth());

        int evicted = subscriptions.upsert(userId, app.appId(), deviceId, endpoint.strip(), request.keys().p256dh(), request.keys().auth(),
                clip(userAgent), clock.instant(), properties.maxSubscriptionsPerApp());
        log.info("Push subscription saved: userId={}, app={}, evicted={}", userId, app.appId(), evicted);
    }

    /** 이 구독을 지운다. 없어도(이미 지웠어도) 오류가 아니고, 남의 구독은 지울 수 없다(조용히 0건). */
    @Transactional
    public int unsubscribe(UUID userId, String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new NotifyException(ErrorCode.INVALID_INPUT);
        }
        return subscriptions.deleteByEndpoint(userId, endpoint.strip());
    }

    /** 이 앱으로 등록된 구독만 지운다. 없어도 오류가 아니다. */
    @Transactional
    public int unsubscribe(UUID userId, String appId, String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new NotifyException(ErrorCode.INVALID_INPUT);
        }
        return subscriptions.deleteByEndpointInApp(userId, appId, endpoint.strip());
    }

    /** 이 기기의 내 모든 앱 구독을 지운다(로그아웃 때). */
    @Transactional
    public int removeDevice(UUID userId, String deviceId) {
        if (deviceId == null || !DEVICE_ID.matcher(deviceId).matches()) {
            throw new NotifyException(ErrorCode.INVALID_INPUT, "기기 표식(deviceId)이 올바르지 않습니다.");
        }
        int removed = subscriptions.deleteByDevice(userId, deviceId);
        log.info("Push device removed: userId={}, subscriptions={}", userId, removed);
        return removed;
    }

    /** 내 기기 목록: 기기(deviceId)별로 묶고 앱별 구독 상태를 보여 준다. endpoint·키는 주지 않는다. */
    @Transactional(readOnly = true)
    public DevicesResponse devices(UUID userId) {
        Map<String, String> appNames = new LinkedHashMap<>();
        registry.apps().forEach(app -> appNames.put(app.appId(), app.displayName()));
        Map<String, List<Subscription>> byDevice = new LinkedHashMap<>();
        for (Subscription subscription : subscriptions.findByUser(userId)) {
            byDevice.computeIfAbsent(subscription.deviceId(), k -> new java.util.ArrayList<>()).add(subscription);
        }
        List<Device> devices = byDevice.entrySet().stream().map(entry -> {
            List<Subscription> subs = entry.getValue();
            Instant lastSeen = subs.stream().map(Subscription::lastSeenAt).max(Comparator.naturalOrder()).orElse(null);
            Instant lastSuccess = subs.stream().map(Subscription::lastSuccessAt).filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
            String agent = subs.stream().map(Subscription::userAgent).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            List<DeviceApp> apps = subs.stream()
                    .map(s -> new DeviceApp(s.appId(), appNames.getOrDefault(s.appId(), s.appId()), s.createdAt(), s.lastSuccessAt()))
                    .toList();
            return new Device(entry.getKey(), UserAgentLabel.of(agent), lastSeen, lastSuccess, apps);
        }).sorted(Comparator.comparing(Device::lastSeenAt, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
        return new DevicesResponse(devices);
    }

    private static void requireValidKeys(String p256dh, String auth) {
        if (p256dh == null || auth == null || p256dh.length() > KEY_MAX || auth.length() > AUTH_MAX) {
            throw new NotifyException(ErrorCode.INVALID_INPUT, "푸시 구독 키가 올바르지 않습니다.");
        }
        try {
            WebPushEncryptor.validateSubscriptionKeys(p256dh, auth);
        } catch (IllegalArgumentException e) {
            throw new NotifyException(ErrorCode.INVALID_INPUT, "푸시 구독 키가 올바르지 않습니다.");
        }
    }

    private static String clip(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() <= USER_AGENT_MAX ? userAgent : userAgent.substring(0, USER_AGENT_MAX);
    }
}
