package com.doro.notify.push;

import com.doro.notify.common.exception.ErrorCode;
import com.doro.notify.common.exception.NotifyException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 구독 주소(endpoint)로 받아도 되는 주소인지 판단한다. 푸시를 보낼 때 서버가 이 주소로 직접 접속하므로, 아무 주소나 받으면
 * 사용자가 서버를 내부망(관리 서비스, 메타데이터 주소 등)으로 향하게 만들 수 있다(SSRF). 그래서 알려진 푸시 서비스 도메인
 * (설정의 allowed-hosts)의 https 주소만 받고, IP 주소·계정 정보가 든 주소·다른 포트는 거절한다.
 */
@Component
public class PushEndpointPolicy {

    static final int MAX_LENGTH = 2048;
    private static final int HTTPS_PORT = 443;
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private final PushProperties properties;

    public PushEndpointPolicy(PushProperties properties) {
        this.properties = properties;
    }

    /** @return 검증된 주소. 허용되지 않으면 400 */
    public URI validate(String endpoint) {
        if (endpoint == null || endpoint.isBlank() || endpoint.length() > MAX_LENGTH) {
            throw invalid();
        }
        URI uri;
        try {
            uri = new URI(endpoint.strip());
        } catch (URISyntaxException e) {
            throw invalid();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean schemeAllowed = scheme.equals("https") || (!properties.requireHttps() && scheme.equals("http"));
        if (!schemeAllowed || uri.getUserInfo() != null || uri.getFragment() != null || uri.getHost() == null) {
            throw invalid();
        }
        if (properties.requireHttps() && uri.getPort() != -1 && uri.getPort() != HTTPS_PORT) {
            throw invalid();
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.contains(":") || host.startsWith("[") || IPV4.matcher(host).matches() || !hostAllowed(host)) {
            throw invalid();
        }
        return uri;
    }

    private boolean hostAllowed(String host) {
        return properties.allowedHosts().stream().anyMatch(allowed -> host.equals(allowed) || host.endsWith("." + allowed));
    }

    private static NotifyException invalid() {
        return new NotifyException(ErrorCode.INVALID_INPUT, "지원하지 않는 푸시 구독 주소입니다.");
    }
}
