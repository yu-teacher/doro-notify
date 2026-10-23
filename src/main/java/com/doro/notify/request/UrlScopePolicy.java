package com.doro.notify.request;

import com.doro.notify.common.exception.ErrorCode;
import com.doro.notify.common.exception.NotifyException;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 알림을 눌렀을 때 열 주소({@code url})와 아이콘 주소는 **등록된 앱 범위(scope_path) 안의 같은 출처 경로**여야 한다.
 * 절대 주소·스킴·{@code //호스트}·역슬래시·{@code ..} 로 범위를 벗어나는 경로는 모두 거절한다(피싱·열린 리디렉션 방지).
 * 퍼센트 인코딩({@code %2e%2e}, {@code %5c})도 디코딩한 뒤 같은 규칙으로 본다.
 */
final class UrlScopePolicy {

    private UrlScopePolicy() {
    }

    /** @return 검증을 통과한 같은 문자열. 범위 밖이면 400. */
    static String require(String url, String scopePath, int maxLength) {
        if (url == null || url.isEmpty() || url.length() > maxLength || !url.startsWith("/") || url.startsWith("//")) {
            throw invalid();
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= 0x20 || c == 0x7f || c == '\\') {
                throw invalid();
            }
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw invalid();
        }
        if (uri.getScheme() != null || uri.getRawAuthority() != null || uri.getPath() == null) {
            throw invalid();
        }
        String decoded = uri.getPath();
        if (decoded.indexOf('\\') >= 0 || decoded.indexOf('\u0000') >= 0 || !decoded.startsWith("/") || decoded.startsWith("//")) {
            throw invalid();
        }
        for (String segment : decoded.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                throw invalid();
            }
        }
        if (!decoded.startsWith(scopePath)) {
            throw invalid();
        }
        return url;
    }

    private static NotifyException invalid() {
        return new NotifyException(ErrorCode.INVALID_URL);
    }
}
