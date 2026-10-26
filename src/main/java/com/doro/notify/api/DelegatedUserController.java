package com.doro.notify.api;

import com.doro.notify.common.exception.ErrorCode;
import com.doro.notify.common.exception.NotifyException;
import com.doro.notify.common.response.ApiResponse;
import com.doro.notify.delivery.TestNotificationService;
import com.doro.notify.internal.InternalApiAuthFilter;
import com.doro.notify.preference.PreferenceDtos.AppView;
import com.doro.notify.preference.PreferenceDtos.Change;
import com.doro.notify.preference.PreferenceDtos.PreferencesResponse;
import com.doro.notify.preference.PreferenceDtos.UpdateRequest;
import com.doro.notify.preference.PreferenceService;
import com.doro.notify.registry.AppRegistry;
import com.doro.notify.registry.NotifyApp;
import com.doro.notify.subscription.SubscriptionDtos.RemovedResponse;
import com.doro.notify.subscription.SubscriptionDtos.SubscribeRequest;
import com.doro.notify.subscription.SubscriptionDtos.SubscribeResponse;
import com.doro.notify.subscription.SubscriptionDtos.TestResult;
import com.doro.notify.subscription.SubscriptionDtos.UnsubscribeRequest;
import com.doro.notify.subscription.SubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 서비스가 자기 사용자를 대신해 기기·설정을 다루는 내부 API. 서비스(예: 파티)가 자기 화면의 푸시 설정 API 를 알림 서비스로 넘길 때 쓴다
 * (이관 중 옛 화면이 그대로 동작하게 한다). 사용자의 로그인 토큰을 넘기지 않고, **호출자 서비스가 자기 사용자임을 보증**한다.
 *
 * <p>범위는 호출자 서비스의 앱으로 제한된다: 토큰 이름과 경로의 app 이 같아야 하고(다르면 403), 설정 변경도 그 앱의 종류만 바꿀 수 있고,
 * 구독은 그 앱으로만 등록된다. 다른 앱의 구독·설정은 보이지도 바뀌지도 않는다. 응답에 endpoint·키는 없다.
 */
@RestController
@RequestMapping("/internal/v1/apps/{app}/users/{userId}")
class DelegatedUserController {

    private final AppRegistry registry;
    private final SubscriptionService subscriptions;
    private final PreferenceService preferences;
    private final TestNotificationService tests;

    DelegatedUserController(AppRegistry registry, SubscriptionService subscriptions, PreferenceService preferences, TestNotificationService tests) {
        this.registry = registry;
        this.subscriptions = subscriptions;
        this.preferences = preferences;
        this.tests = tests;
    }

    @PutMapping("/subscriptions")
    ApiResponse<SubscribeResponse> subscribe(HttpServletRequest http, @PathVariable String app, @PathVariable UUID userId,
                                             @RequestHeader(value = "User-Agent", required = false) String userAgent, @RequestBody SubscribeRequest request) {
        NotifyApp known = ownApp(http, app);
        if (request != null && request.app() != null && !request.app().equals(known.appId())) {
            throw new NotifyException(ErrorCode.APP_MISMATCH);
        }
        subscriptions.subscribe(userId, known, request, userAgent);
        return ApiResponse.success(new SubscribeResponse(known.appId(), request.deviceId()));
    }

    /** 이 앱으로 등록된 구독만 지운다(endpoint 가 같아도 다른 앱의 구독은 건드리지 않는다). */
    @DeleteMapping("/subscriptions")
    ApiResponse<RemovedResponse> unsubscribe(HttpServletRequest http, @PathVariable String app, @PathVariable UUID userId, @RequestBody UnsubscribeRequest request) {
        NotifyApp known = ownApp(http, app);
        return ApiResponse.success(new RemovedResponse(subscriptions.unsubscribe(userId, known.appId(), request == null ? null : request.endpoint())));
    }

    @GetMapping("/preferences")
    ApiResponse<PreferencesResponse> preferences(HttpServletRequest http, @PathVariable String app, @PathVariable UUID userId) {
        NotifyApp known = ownApp(http, app);
        return ApiResponse.success(onlyApp(preferences.view(userId), known.appId()));
    }

    @PutMapping("/preferences")
    ApiResponse<PreferencesResponse> updatePreferences(HttpServletRequest http, @PathVariable String app, @PathVariable UUID userId, @RequestBody UpdateRequest request) {
        NotifyApp known = ownApp(http, app);
        List<Change> changes = request == null ? null : request.changes();
        if (changes != null) {
            for (Change change : changes) {
                if (change == null || change.app() == null || !change.app().equals(known.appId())) {
                    throw new NotifyException(ErrorCode.APP_MISMATCH);
                }
            }
        }
        return ApiResponse.success(onlyApp(preferences.update(userId, request), known.appId()));
    }

    /** 시험 알림: 이 앱으로 등록된 사용자의 기기에만 보낸다. */
    @PostMapping("/test")
    ApiResponse<TestResult> test(HttpServletRequest http, @PathVariable String app, @PathVariable UUID userId) {
        NotifyApp known = ownApp(http, app);
        return ApiResponse.success(tests.send(userId, known));
    }

    /** 호출자는 자기 이름과 같은 app 만 다룰 수 있다. 호출자를 확인하지 못한 경우(도입 중 WARN·OFF 모드)에는 비교할 수 없어 통과한다. */
    private NotifyApp ownApp(HttpServletRequest http, String app) {
        String caller = (String) http.getAttribute(InternalApiAuthFilter.CALLER_ATTRIBUTE);
        if (caller != null && !caller.equals(app)) {
            throw new NotifyException(ErrorCode.APP_MISMATCH);
        }
        return registry.findApp(app).orElseThrow(() -> new NotifyException(ErrorCode.UNKNOWN_APP));
    }

    private static PreferencesResponse onlyApp(PreferencesResponse all, String appId) {
        List<AppView> mine = all.apps().stream().filter(a -> a.app().equals(appId)).toList();
        return new PreferencesResponse(mine);
    }
}
