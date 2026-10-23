package com.doro.notify.api;

import com.doro.notify.internal.InternalApiAuthFilter;
import com.doro.notify.internal.InternalAuthMode;
import com.doro.notify.internal.InternalCallerTokens;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 호출자 토큰 필터의 모드(OFF·WARN·ENFORCE)와 토큰 목록 해석. 통합 시험은 ENFORCE 만 돌리므로 나머지 단계는 여기서 본다. */
class InternalAuthFilterTest {

    private static final String PARTY = "party-token-" + "p".repeat(40);
    private static final String BLOG = "blog-token-" + "b".repeat(40);
    private final FilterChain chain = mock(FilterChain.class);

    private MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/v1/notifications");
        request.setRequestURI("/internal/v1/notifications");
        if (token != null) {
            request.addHeader("X-Doro-Service-Token", token);
        }
        return request;
    }

    @Test
    @DisplayName("ENFORCE: 맞는 토큰이면 호출자 이름이 요청 속성에 실리고, 아니면 401 이며 체인을 타지 않는다")
    void enforce() throws Exception {
        InternalApiAuthFilter filter = new InternalApiAuthFilter("ENFORCE", "party:" + PARTY + ",blog:" + BLOG);
        MockHttpServletRequest ok = request(BLOG);
        filter.doFilter(ok, new MockHttpServletResponse(), chain);
        assertThat(ok.getAttribute(InternalApiAuthFilter.CALLER_ATTRIBUTE)).isEqualTo("blog");
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        FilterChain blocked = mock(FilterChain.class);
        MockHttpServletResponse denied = new MockHttpServletResponse();
        filter.doFilter(request("nope"), denied, blocked);
        assertThat(denied.getStatus()).isEqualTo(401);
        assertThat(denied.getContentAsString()).doesNotContain("nope").contains("\"success\":false");
        verify(blocked, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("토큰이 하나도 설정되지 않은 ENFORCE 는 모든 내부 요청을 거절한다(기본값은 닫힘)")
    void enforceWithoutTokensRejectsAll() throws Exception {
        InternalApiAuthFilter filter = new InternalApiAuthFilter("", "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(PARTY), response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("WARN: 토큰이 틀려도 통과시키되 호출자는 알 수 없고, OFF: 검사하지 않는다")
    void warnAndOff() throws Exception {
        for (String mode : new String[] {"WARN", "OFF"}) {
            FilterChain next = mock(FilterChain.class);
            MockHttpServletRequest request = request("nope");
            MockHttpServletResponse response = new MockHttpServletResponse();
            new InternalApiAuthFilter(mode, "party:" + PARTY).doFilter(request, response, next);
            assertThat(response.getStatus()).as(mode).isEqualTo(200);
            assertThat(request.getAttribute(InternalApiAuthFilter.CALLER_ATTRIBUTE)).isNull();
            verify(next).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
    }

    @Test
    @DisplayName("모드와 관계없이 X-Forwarded-For·X-Real-IP 가 붙은 요청은 404 이고, 내부 경로가 아니면 검사하지 않는다")
    void gatewayTrafficAndOtherPaths() throws Exception {
        for (String mode : new String[] {"OFF", "WARN", "ENFORCE"}) {
            MockHttpServletRequest forwarded = request(PARTY);
            forwarded.addHeader("X-Forwarded-For", "203.0.113.1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            new InternalApiAuthFilter(mode, "party:" + PARTY).doFilter(forwarded, response, chain);
            assertThat(response.getStatus()).as(mode).isEqualTo(404);
        }
        MockHttpServletRequest api = new MockHttpServletRequest("GET", "/api/v1/notify/config");
        api.setRequestURI("/api/v1/notify/config");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain next = mock(FilterChain.class);
        new InternalApiAuthFilter("ENFORCE", "party:" + PARTY).doFilter(api, response, next);
        verify(next).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("토큰 목록: 짧은 토큰·형식 오류·중복 이름은 기동 때 거부하고, 메시지에 토큰 값을 싣지 않는다")
    void tokenListValidation() {
        assertThatThrownBy(() -> InternalCallerTokens.parse("party:short")).isInstanceOf(IllegalStateException.class).hasMessageContaining("party");
        assertThatThrownBy(() -> InternalCallerTokens.parse("no-colon-" + PARTY)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(PARTY);
        assertThatThrownBy(() -> InternalCallerTokens.parse("party:" + PARTY + ",party:" + BLOG)).isInstanceOf(IllegalStateException.class).hasMessageContaining("중복");
        assertThatThrownBy(() -> InternalAuthMode.parse("MAYBE")).isInstanceOf(IllegalStateException.class);
        assertThat(InternalAuthMode.parse(null)).isEqualTo(InternalAuthMode.ENFORCE);
        InternalCallerTokens tokens = InternalCallerTokens.parse(" party : " + PARTY + " , ");
        assertThat(tokens.size()).isEqualTo(1);
        assertThat(tokens.callerOf(PARTY)).contains("party");
        assertThat(tokens.callerOf(PARTY + "x")).isEmpty();
        assertThat(tokens.callerOf(null)).isEmpty();
    }
}
