package com.doro.notify.internal;

import com.doro.notify.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * 서비스 간 내부 API(/internal/**)를 지킨다. Doro IAM 의 {@code InternalApiAuthFilter} 와 같은 방식이다.
 *
 * <ol>
 *   <li><b>게이트웨이를 거친 요청은 없는 경로처럼 404.</b> 내부 API 는 게이트웨이가 라우팅하지 않으므로 {@code X-Forwarded-For}/{@code X-Real-IP} 가
 *       붙은 요청은 외부에서 온 것이다. 존재 여부도 알려 주지 않는다.</li>
 *   <li><b>호출자 서비스 토큰.</b> 헤더 {@value #TOKEN_HEADER} 의 토큰이 설정된 {@code 이름:토큰} 중 하나와 같아야 한다.
 *       모드 OFF/WARN/ENFORCE: ENFORCE(기본)는 실패하면 401, WARN 은 경고만 남기고 통과(도입 중 확인용), OFF 는 검사하지 않는다.</li>
 * </ol>
 * 확인된 호출자 이름은 요청 속성 {@value #CALLER_ATTRIBUTE} 에 실린다. 컨트롤러는 이 이름과 요청의 app 이 같은지 확인한다(호출자는 자기 app 만 쓴다).
 * WARN·OFF 모드에서는 이 속성이 없을 수 있다(그 모드는 도입 중에만 쓴다). 토큰 값은 로그에 남기지 않는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class InternalApiAuthFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Doro-Service-Token";
    public static final String CALLER_ATTRIBUTE = "notify.internal.caller";
    private static final String INTERNAL_PREFIX = "/internal/";

    private static final Logger log = LoggerFactory.getLogger(InternalApiAuthFilter.class);

    private final InternalAuthMode mode;
    private final InternalCallerTokens tokens;

    public InternalApiAuthFilter(
            @Value("${notify.internal-auth.mode:ENFORCE}") String mode,
            @Value("${notify.internal-auth.tokens:}") String tokens) {
        this.mode = InternalAuthMode.parse(mode);
        this.tokens = InternalCallerTokens.parse(tokens);
        if (this.mode == InternalAuthMode.ENFORCE && this.tokens.isEmpty()) {
            log.warn("Internal API auth is ENFORCE but no caller tokens are configured (NOTIFY_INTERNAL_TOKENS): every /internal request will be rejected");
        } else if (this.mode != InternalAuthMode.ENFORCE) {
            log.warn("Internal API auth mode is {} (callers={}): set NOTIFY_INTERNAL_AUTH_MODE=ENFORCE once every caller sends its token", this.mode, this.tokens.size());
        } else {
            log.info("Internal API auth enforced for {} caller(s)", this.tokens.size());
        }
    }

    /** 컨트롤러 매핑이 보는 것과 같은 경로: 퍼센트 디코딩·세미콜론 매개변수 제거·중복 슬래시와 ./.. 정리(문자열 비교를 우회하는 경로 방지). */
    static String canonical(HttpServletRequest request) {
        return StringUtils.cleanPath(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = canonical(request);
        return !(uri.equals("/internal") || uri.startsWith(INTERNAL_PREFIX));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getHeader("X-Forwarded-For") != null || request.getHeader("X-Real-IP") != null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (mode == InternalAuthMode.OFF) {
            chain.doFilter(request, response);
            return;
        }
        Optional<String> caller = tokens.callerOf(request.getHeader(TOKEN_HEADER));
        if (caller.isPresent()) {
            request.setAttribute(CALLER_ATTRIBUTE, caller.get());
            log.debug("Internal API call: caller={}, path={}", caller.get(), canonical(request));
            chain.doFilter(request, response);
            return;
        }
        boolean hadToken = request.getHeader(TOKEN_HEADER) != null;
        if (mode == InternalAuthMode.WARN) {
            log.warn("Internal API call without a valid caller token (allowed in WARN mode): path={}, tokenPresented={}", canonical(request), hadToken);
            chain.doFilter(request, response);
            return;
        }
        log.warn("Internal API call rejected: path={}, tokenPresented={}", canonical(request), hadToken);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        ErrorCode code = ErrorCode.UNAUTHORIZED;
        // 표준 응답 봉투와 같은 모양. 필터에서는 JSON 변환기에 기대지 않고 고정 문구를 직접 쓴다.
        response.getWriter().write("{\"success\":false,\"code\":\"" + code.code() + "\",\"message\":\"" + code.message() + "\",\"status\":401,\"timestamp\":\""
                + Instant.now() + "\"}");
    }
}
