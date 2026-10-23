package com.doro.notify.api;

import com.doro.notify.account.CurrentUser;
import com.doro.notify.common.exception.ErrorCode;
import com.doro.notify.common.exception.NotifyException;
import com.doro.notify.common.response.ApiResponse;
import com.doro.notify.delivery.TestNotificationService;
import com.doro.notify.preference.PreferenceDtos.PreferencesResponse;
import com.doro.notify.preference.PreferenceDtos.UpdateRequest;
import com.doro.notify.preference.PreferenceService;
import com.doro.notify.registry.NotifyApp;
import com.doro.notify.subscription.SubscriptionDtos.ConfigResponse;
import com.doro.notify.subscription.SubscriptionDtos.DevicesResponse;
import com.doro.notify.subscription.SubscriptionDtos.RemovedResponse;
import com.doro.notify.subscription.SubscriptionDtos.SubscribeRequest;
import com.doro.notify.subscription.SubscriptionDtos.SubscribeResponse;
import com.doro.notify.subscription.SubscriptionDtos.TestResult;
import com.doro.notify.subscription.SubscriptionDtos.UnsubscribeRequest;
import com.doro.notify.subscription.SubscriptionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 클라이언트용 API(설계 7.1). 로그인 사용자 본인의 기기·설정만 다룬다: 사용자 id 는 토큰에서만 얻고 경로·본문의 id 를 믿지 않는다.
 * 게이트웨이 경유 경로는 두 가지다 — 앱 접두사 아래({@code /party/api/v1/notify/…}, 게이트웨이가 {@code X-Doro-Notify-App} 을 붙인다)와
 * 허브({@code /notify/api/v1/…}, Bearer 직접, 헤더 없음). 허브 경로는 설정 조회·변경과 기기 해제만 되고 구독 등록은 앱 경로에서만 된다.
 */
@RestController
@RequestMapping("/api/v1/notify")
class NotifyClientController {

    private final CurrentUser currentUser;
    private final ClientApp clientApp;
    private final SubscriptionService subscriptions;
    private final PreferenceService preferences;
    private final TestNotificationService tests;

    NotifyClientController(CurrentUser currentUser, ClientApp clientApp, SubscriptionService subscriptions,
                           PreferenceService preferences, TestNotificationService tests) {
        this.currentUser = currentUser;
        this.clientApp = clientApp;
        this.subscriptions = subscriptions;
        this.preferences = preferences;
        this.tests = tests;
    }

    /** VAPID 공개 키와 서버 사용 가능 여부. 공개 정보라서 로그인 없이도 읽을 수 있다. */
    @GetMapping("/config")
    ApiResponse<ConfigResponse> config() {
        return ApiResponse.success(subscriptions.config());
    }

    @PutMapping("/subscriptions")
    ApiResponse<SubscribeResponse> subscribe(@RequestHeader(value = ClientApp.HEADER, required = false) String appHeader,
                                             @RequestHeader(value = "User-Agent", required = false) String userAgent,
                                             @RequestBody SubscribeRequest request) {
        UUID userId = currentUser.require();
        NotifyApp app = clientApp.require(appHeader);
        if (request != null && request.app() != null && !request.app().equals(app.appId())) {
            // 본문의 app 은 신뢰하지 않는다: 헤더(게이트웨이가 정한 앱)와 다르면 다른 앱 행세로 본다
            throw new NotifyException(ErrorCode.APP_MISMATCH);
        }
        subscriptions.subscribe(userId, app, request, userAgent);
        return ApiResponse.success(new SubscribeResponse(app.appId(), request.deviceId()));
    }

    @DeleteMapping("/subscriptions")
    ApiResponse<RemovedResponse> unsubscribe(@RequestBody UnsubscribeRequest request) {
        UUID userId = currentUser.require();
        return ApiResponse.success(new RemovedResponse(subscriptions.unsubscribe(userId, request == null ? null : request.endpoint())));
    }

    @DeleteMapping("/devices/{deviceId}")
    ApiResponse<RemovedResponse> removeDevice(@PathVariable String deviceId) {
        UUID userId = currentUser.require();
        return ApiResponse.success(new RemovedResponse(subscriptions.removeDevice(userId, deviceId)));
    }

    @GetMapping("/devices")
    ApiResponse<DevicesResponse> devices() {
        return ApiResponse.success(subscriptions.devices(currentUser.require()));
    }

    @GetMapping("/preferences")
    ApiResponse<PreferencesResponse> preferences() {
        return ApiResponse.success(preferences.view(currentUser.require()));
    }

    @PutMapping("/preferences")
    ApiResponse<PreferencesResponse> updatePreferences(@RequestBody UpdateRequest request) {
        return ApiResponse.success(preferences.update(currentUser.require(), request));
    }

    /** 내 기기로 시험 알림. 앱 경로에서 부르면 그 앱의 기기만, 허브 경로에서 부르면 내 모든 기기. */
    @PostMapping("/test")
    ApiResponse<TestResult> test(@RequestHeader(value = ClientApp.HEADER, required = false) String appHeader) {
        UUID userId = currentUser.require();
        NotifyApp app = clientApp.resolve(appHeader).orElse(null);
        return ApiResponse.success(tests.send(userId, app));
    }
}
