package com.doro.notify.common.exception;

public class NotifyException extends RuntimeException {

    private final ErrorCode errorCode;

    public NotifyException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public NotifyException(ErrorCode errorCode, String detailMessage) {
        super(detailMessage);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
