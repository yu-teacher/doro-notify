package com.doro.notify.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    // 400 Bad Request
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON-400-01", "잘못된 입력값입니다."),
    INVALID_URL(HttpStatus.BAD_REQUEST, "NOTIFY-400-01", "알림 주소는 등록된 앱 범위 안의 상대 경로여야 합니다."),
    UNKNOWN_APP(HttpStatus.BAD_REQUEST, "NOTIFY-400-02", "등록되지 않은 앱입니다."),
    UNKNOWN_TYPE(HttpStatus.BAD_REQUEST, "NOTIFY-400-03", "등록되지 않은 알림 종류입니다."),
    TOO_MANY_RECIPIENTS(HttpStatus.BAD_REQUEST, "NOTIFY-400-04", "수신자 수가 상한을 넘었습니다."),
    PAYLOAD_LIMIT(HttpStatus.BAD_REQUEST, "NOTIFY-400-05", "알림 내용이 너무 큽니다."),

    // 401 Unauthorized
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AUTH-401-01", "로그인이 필요합니다."),

    // 403 Forbidden
    APP_HEADER_REQUIRED(HttpStatus.FORBIDDEN, "NOTIFY-403-01", "이 경로로는 구독을 등록할 수 없습니다."),
    APP_MISMATCH(HttpStatus.FORBIDDEN, "NOTIFY-403-02", "호출자가 이 앱의 알림을 다룰 수 없습니다."),

    // 404 Not Found
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON-404-01", "요청한 리소스를 찾을 수 없습니다."),

    // 405
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON-405-01", "지원하지 않는 HTTP 메서드입니다."),

    // 413
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "COMMON-413-01", "요청 본문이 너무 큽니다."),

    // 415
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "COMMON-415-01", "지원하지 않는 요청 형식입니다."),

    // 406
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "COMMON-406-01", "요청한 응답 형식을 제공할 수 없습니다."),

    // 500 / 503
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "SYS-500-01", "서버 내부 오류가 발생했습니다."),
    PUSH_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "NOTIFY-503-01", "이 서버는 푸시 알림을 쓸 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
