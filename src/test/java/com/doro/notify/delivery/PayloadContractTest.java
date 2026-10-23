package com.doro.notify.delivery;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 푸시 페이로드 계약 v1(설계 7.3)을 고정한다. 앱별 서비스 워커(클라이언트 쪽 복사본)가 이 모양만 안다고 가정하므로,
 * 키 이름·순서·타입이 바뀌면 이 시험이 먼저 깨져야 한다.
 */
class PayloadContractTest {

    private static String json(String tag, String icon) {
        return new String(PayloadV1.build("party", "comment", "핀 이름", "작성자: 댓글", tag, "/party/?map=1&pin=2", icon), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("모든 필드가 있을 때의 정확한 JSON(키 순서 포함)")
    void exactShape() {
        assertThat(json("pin:1", "/party/icon-192.png")).isEqualTo(
                "{\"v\":1,\"app\":\"party\",\"type\":\"comment\",\"title\":\"핀 이름\",\"body\":\"작성자: 댓글\",\"tag\":\"pin:1\","
                        + "\"url\":\"/party/?map=1&pin=2\",\"icon\":\"/party/icon-192.png\"}");
    }

    @Test
    @DisplayName("v 는 숫자 1 이고 필수 키(v, app, type, title, body, url)는 항상 있으며 tag·icon 은 없으면 키를 싣지 않는다")
    void requiredAndOptionalKeys() {
        String full = json("pin:1", "/party/i.png");
        assertThat(JsonPath.<Integer>read(full, "$.v")).isEqualTo(1);
        assertThat(JsonPath.<Map<String, Object>>read(full, "$").keySet()).containsExactly("v", "app", "type", "title", "body", "tag", "url", "icon");

        String minimal = json(null, null);
        assertThat(JsonPath.<Map<String, Object>>read(minimal, "$").keySet()).containsExactly("v", "app", "type", "title", "body", "url");
        assertThat(json("", "")).isEqualTo(minimal);
    }

    @Test
    @DisplayName("모든 값은 문자열이고(v 제외) 따옴표·역슬래시·줄바꿈·제어 문자·이모지·U+2028 이 JSON 으로 안전하게 이스케이프된다")
    void escapesSafely() {
        String title = "따옴표\" 역슬래시\\ 줄\n탭\t제어\u0001 이모지😀 줄구분 ";
        String text = new String(PayloadV1.build("party", "comment", title, "</script>", "t", "/party/", null), StandardCharsets.UTF_8);
        assertThat(JsonPath.<String>read(text, "$.title")).isEqualTo(title);
        assertThat(JsonPath.<String>read(text, "$.body")).isEqualTo("</script>");
        assertThat(text).doesNotContain("\n").doesNotContain(" ").doesNotContain("\u0001");
        for (String key : List.of("app", "type", "title", "body", "tag", "url")) {
            assertThat(JsonPath.<Object>read(text, "$." + key)).isInstanceOf(String.class);
        }
    }
}
