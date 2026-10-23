package com.doro.notify.api;

import com.doro.notify.common.exception.ErrorCode;
import com.doro.notify.common.exception.NotifyException;
import com.doro.notify.common.response.ApiResponse;
import com.doro.notify.delivery.DeliveryJobRepository;
import com.doro.notify.internal.InternalApiAuthFilter;
import com.doro.notify.registry.AppRegistry;
import com.doro.notify.registry.NotifyApp;
import com.doro.notify.registry.NotifyType;
import com.doro.notify.request.NotificationDtos.NotificationRequest;
import com.doro.notify.request.NotificationDtos.NotificationResult;
import com.doro.notify.request.NotificationDtos.TypeRegistration;
import com.doro.notify.request.NotificationDtos.TypesRequest;
import com.doro.notify.request.NotificationRequestService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 서비스 간 내부 API(설계 7.2). 게이트웨이가 라우팅하지 않고, 호출자 서비스 토큰({@link InternalApiAuthFilter})을 통과한 요청만 온다.
 * 호출자는 **자기 이름과 같은 app** 으로만 요청할 수 있다(다르면 403): 토큰 이름은 알림 앱 id 와 같다.
 */
@RestController
@RequestMapping("/internal/v1")
class InternalController {

    private static final Pattern TYPE_ID = Pattern.compile("^[a-z][a-z0-9_-]{0,47}$");
    private static final int MAX_TYPES = 50;
    private static final int LABEL_MAX = 60;
    private static final int DESCRIPTION_MAX = 200;

    public record RegisteredTypes(String app, int registered) {
    }

    public record Stats(Map<String, Long> jobsByStatus, Long oldestPendingAgeSeconds) {
    }

    private final NotificationRequestService notifications;
    private final AppRegistry registry;
    private final DeliveryJobRepository jobs;
    private final Clock clock;

    InternalController(NotificationRequestService notifications, AppRegistry registry, DeliveryJobRepository jobs, Clock clock) {
        this.notifications = notifications;
        this.registry = registry;
        this.jobs = jobs;
        this.clock = clock;
    }

    /** 알림 요청. 응답은 큐 적재 기준이고 실제 전송은 비동기다. 같은 (호출자, 멱등 키) 재요청은 200 + outcome=DUPLICATE. */
    @PostMapping("/notifications")
    ApiResponse<NotificationResult> notifications(HttpServletRequest http, @RequestBody NotificationRequest request) {
        String caller = (String) http.getAttribute(InternalApiAuthFilter.CALLER_ATTRIBUTE);
        requireOwnApp(caller, request == null ? null : request.app());
        return ApiResponse.success(notifications.accept(caller, request));
    }

    /**
     * 앱의 알림 종류 등록(upsert, 멱등). 보낸 목록이 **현재 쓰는 종류 전체**다: 목록에 없는 기존 종류는 지우지 않고 deprecated 로 표시한다.
     */
    @PutMapping("/apps/{app}/types")
    ApiResponse<RegisteredTypes> registerTypes(HttpServletRequest http, @PathVariable String app, @RequestBody TypesRequest request) {
        String caller = (String) http.getAttribute(InternalApiAuthFilter.CALLER_ATTRIBUTE);
        requireOwnApp(caller, app);
        NotifyApp known = registry.findApp(app).orElseThrow(() -> new NotifyException(ErrorCode.UNKNOWN_APP));
        List<TypeRegistration> items = request == null ? null : request.types();
        if (items == null || items.isEmpty() || items.size() > MAX_TYPES) {
            throw new NotifyException(ErrorCode.INVALID_INPUT);
        }
        Set<String> seen = new HashSet<>();
        List<NotifyType> types = new ArrayList<>();
        for (TypeRegistration item : items) {
            if (item == null || item.type() == null || !TYPE_ID.matcher(item.type()).matches() || !seen.add(item.type())
                    || item.label() == null || item.label().isBlank() || item.label().length() > LABEL_MAX
                    || (item.description() != null && item.description().length() > DESCRIPTION_MAX)) {
                throw new NotifyException(ErrorCode.INVALID_INPUT);
            }
            types.add(new NotifyType(known.appId(), item.type(), item.label().strip(), item.description() == null ? "" : item.description(),
                    item.defaultOn() == null || item.defaultOn(), false));
        }
        registry.registerTypes(known.appId(), types);
        return ApiResponse.success(new RegisteredTypes(known.appId(), types.size()));
    }

    /** 운영 점검용 상태: 발송 작업의 상태별 건수와 가장 오래 기다린 PENDING 작업의 나이(초). 알림 내용은 없다. */
    @GetMapping("/stats")
    ApiResponse<Stats> stats() {
        Instant oldest = jobs.oldestPendingCreatedAt();
        Long age = oldest == null ? null : Math.max(0, Duration.between(oldest, clock.instant()).toSeconds());
        return ApiResponse.success(new Stats(jobs.countByStatus(), age));
    }

    /**
     * 호출자는 자기 이름의 app 으로만 요청할 수 있다. 호출자를 확인하지 못한 경우(도입 중 WARN·OFF 모드)에는 비교할 수 없어 통과한다.
     */
    private static void requireOwnApp(String caller, String app) {
        if (app == null) {
            throw new NotifyException(ErrorCode.INVALID_INPUT, "app 이 필요합니다.");
        }
        if (caller != null && !caller.equals(app)) {
            throw new NotifyException(ErrorCode.APP_MISMATCH);
        }
    }
}
