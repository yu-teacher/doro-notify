package com.doro.notify.api;

import com.doro.notify.common.exception.ErrorCode;
import com.doro.notify.common.exception.NotifyException;
import com.doro.notify.registry.AppRegistry;
import com.doro.notify.registry.NotifyApp;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 클라이언트 API 를 호출한 **앱**. 본문이 아니라 게이트웨이가 앱별 location 에서 붙이는 헤더 {@value #HEADER} 로만 정해진다(클라이언트가 다른 앱 행세를 못 하게).
 * 허브 경로(Bearer 직접)에서는 이 헤더가 오지 않는다. 이 값을 믿을 수 있는 것은 게이트웨이가 모든 경로에서 이 헤더를 덮어쓰거나 지운다는 전제 위에서다
 * (docs/DESIGN.md 의 "신뢰 전제").
 */
@Component
class ClientApp {

    static final String HEADER = "X-Doro-Notify-App";

    private final AppRegistry registry;

    ClientApp(AppRegistry registry) {
        this.registry = registry;
    }

    /** 헤더가 없으면 빈 값. 있는데 등록된 앱이 아니면 400. */
    Optional<NotifyApp> resolve(String header) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(registry.findApp(header.strip()).orElseThrow(() -> new NotifyException(ErrorCode.UNKNOWN_APP)));
    }

    /** 앱 경로 전용 쓰기(구독 등록)에서 쓴다: 헤더가 없으면(허브 경로) 403. */
    NotifyApp require(String header) {
        return resolve(header).orElseThrow(() -> new NotifyException(ErrorCode.APP_HEADER_REQUIRED));
    }
}
