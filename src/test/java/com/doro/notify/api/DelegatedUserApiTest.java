package com.doro.notify.api;

import com.doro.notify.push.PushTestSupport.TestDevice;
import com.doro.notify.support.AbstractNotifyTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 서비스가 자기 사용자를 대신해 기기·설정을 다루는 내부 API: 자기 앱으로만, 다른 앱은 보이지도 바뀌지도 않는다. */
class DelegatedUserApiTest extends AbstractNotifyTest {

    final UUID alice = UUID.randomUUID();

    @BeforeEach
    void types() {
        registerType("party", "comment", true);
        registerType("party", "friend", true);
        registerType("blog", "comment", true);
    }

    static String base(String app, UUID user) {
        return "/internal/v1/apps/" + app + "/users/" + user;
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject("select count(*) from " + sql, Integer.class, args);
    }

    @Test
    @DisplayName("구독 등록·해제: 호출자 앱으로만 등록되고, 해제도 그 앱의 구독만 지운다")
    void subscribeAndUnsubscribeInOwnApp() throws Exception {
        TestDevice device = new TestDevice();
        perform(internal(put(base("party", alice) + "/subscriptions"), PARTY_TOKEN).content(subscribeBody(device, "party-abc123")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.app").value("party")).andExpect(jsonPath("$.data.deviceId").value("party-abc123"));
        assertThat(count("push_subscriptions where user_id = ? and app_id = 'party'", alice)).isEqualTo(1);

        // 다른 앱 토큰으로는 같은 경로를 쓸 수 없다
        perform(internal(put(base("party", alice) + "/subscriptions"), BLOG_TOKEN).content(subscribeBody(new TestDevice(), "x"))).andExpect(status().isForbidden());
        assertThat(count("push_subscriptions where user_id = ?", alice)).isEqualTo(1);

        // 본문의 app 이 경로와 다르면 거절
        perform(internal(put(base("party", alice) + "/subscriptions"), PARTY_TOKEN)
                .content(subscribeBody(new TestDevice(), "y").replace("{\"deviceId\"", "{\"app\":\"blog\",\"deviceId\"")))
                .andExpect(status().isForbidden());

        // 같은 endpoint 가 blog 로도 등록돼 있어도(다른 행) 파티 경로의 해제는 파티 것만 지운다
        jdbc.update("insert into push_subscriptions(id,user_id,app_id,device_id,endpoint,p256dh,auth,fail_count,created_at,last_seen_at) values (gen_random_uuid(),?,?,?,?,?,?,0,now(),now())",
                alice, "blog", "b-dev", "https://localhost/blog-only", "p", "a");
        perform(internal(delete(base("party", alice) + "/subscriptions"), PARTY_TOKEN).content("{\"endpoint\":\"https://localhost/blog-only\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.removed").value(0));
        assertThat(count("push_subscriptions where app_id = 'blog'")).isEqualTo(1);
        perform(internal(delete(base("party", alice) + "/subscriptions"), PARTY_TOKEN).content("{\"endpoint\":\"" + device.endpoint + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.removed").value(1));
        assertThat(count("push_subscriptions where app_id = 'party'")).isZero();
    }

    @Test
    @DisplayName("설정 조회·변경: 호출자 앱의 종류만 보이고, 다른 앱의 종류를 바꾸려 하면 403")
    void preferencesAreScopedToTheApp() throws Exception {
        perform(internal(get(base("party", alice) + "/preferences"), PARTY_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apps.length()").value(1))
                .andExpect(jsonPath("$.data.apps[0].app").value("party"))
                .andExpect(jsonPath("$.data.apps[0].types.length()").value(2));

        perform(internal(put(base("party", alice) + "/preferences"), PARTY_TOKEN).content("{\"changes\":[{\"app\":\"party\",\"type\":\"comment\",\"enabled\":false}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apps[0].types[?(@.type=='comment')].enabled").value(false))
                .andExpect(jsonPath("$.data.apps[0].types[?(@.type=='friend')].enabled").value(true));
        assertThat(count("notify_preferences where user_id = ? and app_id = 'party' and type_id = 'comment' and enabled = false", alice)).isEqualTo(1);

        perform(internal(put(base("party", alice) + "/preferences"), PARTY_TOKEN).content("{\"changes\":[{\"app\":\"blog\",\"type\":\"comment\",\"enabled\":false}]}"))
                .andExpect(status().isForbidden());
        assertThat(count("notify_preferences where app_id = 'blog'")).isZero();
        perform(internal(get(base("party", alice) + "/preferences"), BLOG_TOKEN)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("시험 알림: 그 앱으로 등록된 기기에만 보낸다. 토큰 없으면 401")
    void testGoesToOwnAppDevicesOnly() throws Exception {
        TestDevice party = subscribe(alice, "party", "party-dev");
        TestDevice blog = subscribe(alice, "blog", "blog-dev");
        perform(internal(post(base("party", alice) + "/test"), PARTY_TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devices").value(1)).andExpect(jsonPath("$.data.delivered").value(1));
        assertThat(party.received()).hasSize(1);
        assertThat(blog.received()).isEmpty();
        perform(post(base("party", alice) + "/test").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("게이트웨이를 거친 요청은 토큰이 맞아도 404, 알 수 없는 앱은 403(토큰 이름과 다르므로)")
    void gatewayTrafficAndUnknownApp() throws Exception {
        perform(internal(get(base("party", alice) + "/preferences"), PARTY_TOKEN).header("X-Forwarded-For", "203.0.113.9")).andExpect(status().isNotFound());
        perform(internal(get(base("nope", alice) + "/preferences"), PARTY_TOKEN)).andExpect(status().isForbidden());
        perform(internal(get("/internal/v1/apps/party/users/not-a-uuid/preferences"), PARTY_TOKEN)).andExpect(status().isBadRequest());
    }
}
