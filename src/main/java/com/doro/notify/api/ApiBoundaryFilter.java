package com.doro.notify.api;

import com.doro.notify.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * API 경계의 공통 방어. (1) 응답을 어디에도 저장하지 않게 한다({@code Cache-Control: no-store}: 응답이 사용자별 기기·설정이거나 내부 판정이다).
 * (2) 요청 본문 크기 상한: 길이를 알리는 요청은 읽기 전에 413 으로 거절하고, 길이를 숨기는(chunked) 요청은 읽는 도중 상한을 넘으면 읽기를 끊어 본문 오류(400)가 된다.
 * 클라이언트 API 본문은 작은 JSON 이고, 내부 API 는 수신자 목록 때문에 더 크다.
 */
final class ApiBoundaryFilter extends OncePerRequestFilter {

    private final long clientMaxBytes;
    private final long internalMaxBytes;

    ApiBoundaryFilter(long clientMaxBytes, long internalMaxBytes) {
        this.clientMaxBytes = clientMaxBytes;
        this.internalMaxBytes = internalMaxBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/api/") || path.startsWith("/internal/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        long max = request.getRequestURI().startsWith("/internal/") ? internalMaxBytes : clientMaxBytes;
        if (request.getContentLengthLong() > max) {
            ErrorCode code = ErrorCode.PAYLOAD_TOO_LARGE;
            response.setStatus(code.httpStatus().value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"code\":\"" + code.code() + "\",\"message\":\"" + code.message() + "\",\"status\":"
                    + code.httpStatus().value() + ",\"timestamp\":\"" + Instant.now() + "\"}");
            return;
        }
        chain.doFilter(new LimitedRequest(request, max), response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long max;

        LimitedRequest(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream in = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                private int count(int n) throws IOException {
                    if (n > 0) {
                        read += n;
                        if (read > max) {
                            throw new IOException("request body too large");
                        }
                    }
                    return n;
                }

                @Override
                public int read() throws IOException {
                    int b = in.read();
                    if (b >= 0) {
                        count(1);
                    }
                    return b;
                }

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    return count(in.read(buf, off, len));
                }

                @Override
                public boolean isFinished() {
                    return in.isFinished();
                }

                @Override
                public boolean isReady() {
                    return in.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    in.setReadListener(listener);
                }
            };
        }
    }
}
