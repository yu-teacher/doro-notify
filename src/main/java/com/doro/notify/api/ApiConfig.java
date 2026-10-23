package com.doro.notify.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
class ApiConfig {

    /** 내부 호출자 인증 필터(HIGHEST+10) 바로 뒤. 인증 안 된 요청의 본문은 읽기 전에 거절된다. */
    @Bean
    FilterRegistrationBean<ApiBoundaryFilter> apiBoundaryFilter(
            @Value("${notify.api.client-max-body-bytes:16384}") long clientMax,
            @Value("${notify.api.internal-max-body-bytes:262144}") long internalMax) {
        FilterRegistrationBean<ApiBoundaryFilter> registration = new FilterRegistrationBean<>(new ApiBoundaryFilter(clientMax, internalMax));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
