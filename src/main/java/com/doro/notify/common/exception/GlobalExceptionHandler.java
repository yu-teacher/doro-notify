package com.doro.notify.common.exception;

import com.doro.notify.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 모든 오류를 표준 규격으로 바꾼다. 응답에는 내부 메시지(클래스·SQL·스택)를 싣지 않고 일반 문구만 쓰며 상세는 로그에 남긴다.
 * 없는 주소, 비공개 주제, 닫힌 질문은 응답이 똑같아서 존재 여부를 알 수 없다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotifyException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotify(NotifyException e) {
        ErrorCode code = e.errorCode();
        log.warn("NotifyException: code={}, message={}", code.code(), e.getMessage());
        return respond(code, e.getMessage());
    }

    /** 존재하지 않는 경로(스캐너의 /.env, /.git 탐색 등)는 서버 오류가 아니므로 ERROR 로그 없이 404 로 응답한다. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        return respond(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.message());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException e) {
        log.warn("Missing request parameter: {}", e.getParameterName());
        return respond(ErrorCode.INVALID_INPUT, "필수 파라미터가 누락되었습니다: " + e.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("Request parameter type mismatch: name={}", e.getName());
        return respond(ErrorCode.INVALID_INPUT, "파라미터 형식이 올바르지 않습니다: " + e.getName());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("HttpRequestMethodNotSupportedException: {}", e.getMethod());
        return respond(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.message());
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotAcceptable(HttpMediaTypeNotAcceptableException e) {
        log.warn("HttpMediaTypeNotAcceptableException: {}", e.getMessage());
        return respond(ErrorCode.NOT_ACCEPTABLE, ErrorCode.NOT_ACCEPTABLE.message());
    }

    /** 본문이 없거나, JSON 이 아니거나, 모르는 필드가 있는 쓰기 요청. 내부 파싱 메시지는 싣지 않는다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("Unreadable request body: {}", e.getClass().getSimpleName());
        return respond(ErrorCode.INVALID_INPUT, ErrorCode.INVALID_INPUT.message());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        log.warn("HttpMediaTypeNotSupportedException: {}", e.getContentType());
        return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE.message());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception e) {
        log.error("Unhandled system exception: ", e);
        return respond(ErrorCode.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_SERVER_ERROR.message());
    }

    private static ResponseEntity<ApiResponse<Void>> respond(ErrorCode code, String message) {
        // Accept 와 상관없이 오류 본문은 항상 JSON 으로 낸다(XML 만 받겠다는 요청에도 406 의 이유를 알려 준다).
        return ResponseEntity.status(code.httpStatus()).contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(code.httpStatus(), code.code(), message));
    }
}
