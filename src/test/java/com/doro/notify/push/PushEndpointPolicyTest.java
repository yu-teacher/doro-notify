package com.doro.notify.push;

import com.doro.notify.common.exception.NotifyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PushEndpointPolicyTest {

    private final PushEndpointPolicy policy = new PushEndpointPolicy(PushTestKeys.generate().properties(
            "fcm.googleapis.com", "updates.push.services.mozilla.com", "web.push.apple.com", "notify.windows.com"));

    @ParameterizedTest(name = "허용: {0}")
    @ValueSource(strings = {
            "https://fcm.googleapis.com/fcm/send/abc:def_123",
            "https://updates.push.services.mozilla.com/wpush/v2/gAAAA",
            "https://web.push.apple.com/QGxYz",
            "https://wns2-par02p.notify.windows.com/w/?token=abc",
            "https://FCM.GoogleAPIs.com/fcm/send/abc",
            "https://fcm.googleapis.com:443/fcm/send/abc"})
    void allowsKnownPushServices(String endpoint) {
        assertThat(policy.validate(endpoint)).isNotNull();
    }

    @ParameterizedTest(name = "거절: {0}")
    @ValueSource(strings = {
            "http://fcm.googleapis.com/fcm/send/abc",
            "https://169.254.169.254/latest/meta-data/",
            "https://127.0.0.1/x",
            "https://10.0.0.5/x",
            "https://[::1]/x",
            "https://localhost/x",
            "https://fcm.googleapis.com.evil.example/fcm/send/abc",
            "https://evilfcm.googleapis.com/fcm/send/abc",
            "https://fcm.googleapis.com@evil.example/x",
            "https://user:pass@fcm.googleapis.com/x",
            "https://fcm.googleapis.com:8443/x",
            "https://fcm.googleapis.com/x#fragment",
            "ftp://fcm.googleapis.com/x",
            "file:///etc/passwd",
            "//fcm.googleapis.com/x",
            "fcm.googleapis.com/x",
            "https://auth-api:8080/internal",
            "https://doro-guard:8081/x",
            "   ",
            "not a url"})
    void rejectsEverythingElse(String endpoint) {
        assertThatThrownBy(() -> policy.validate(endpoint)).isInstanceOf(NotifyException.class);
    }

    @Test
    @DisplayName("null, 빈 값, 2048자를 넘는 주소는 거절한다")
    void rejectsMissingAndTooLong() {
        assertThatThrownBy(() -> policy.validate(null)).isInstanceOf(NotifyException.class);
        assertThatThrownBy(() -> policy.validate("")).isInstanceOf(NotifyException.class);
        String tooLong = "https://fcm.googleapis.com/" + "a".repeat(PushEndpointPolicy.MAX_LENGTH);
        assertThatThrownBy(() -> policy.validate(tooLong)).isInstanceOf(NotifyException.class);
    }

    @Test
    @DisplayName("허용 도메인 목록이 비어 있으면 아무 주소도 받지 않는다")
    void emptyAllowListAllowsNothing() {
        PushEndpointPolicy none = new PushEndpointPolicy(PushTestKeys.generate().properties());
        assertThatThrownBy(() -> none.validate("https://fcm.googleapis.com/x")).isInstanceOf(NotifyException.class);
    }

    @Test
    @DisplayName("https 요구를 끈 시험 설정에서만 http 와 다른 포트를 받는다")
    void relaxedOnlyWhenHttpsNotRequired() {
        PushProperties relaxed = new PushProperties("k", "p", "mailto:a@b.test", java.util.List.of("localhost"), false,
                java.time.Duration.ofSeconds(5), 10, 5, 2);
        assertThat(new PushEndpointPolicy(relaxed).validate("http://localhost:8123/push/1")).isNotNull();
        // 시험 설정에서도 IP 주소와 허용 목록 밖의 이름은 거절한다
        assertThatThrownBy(() -> new PushEndpointPolicy(relaxed).validate("http://127.0.0.1:8123/push/1")).isInstanceOf(NotifyException.class);
        assertThatThrownBy(() -> new PushEndpointPolicy(relaxed).validate("http://example.test:8123/push/1")).isInstanceOf(NotifyException.class);
    }
}
