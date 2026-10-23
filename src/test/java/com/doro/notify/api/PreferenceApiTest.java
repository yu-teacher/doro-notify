package com.doro.notify.api;

import com.doro.notify.support.AbstractNotifyTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 설정: 종류 등록부(라벨·기본값)와 합쳐 보여 주고, 본인 설정만 바꾼다. 허브·앱 어느 경로에서든 같은 값이다. */
class PreferenceApiTest extends AbstractNotifyTest {

    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();

    @BeforeEach
    void types() {
        jdbc.update("insert into notify_types (app_id, type_id, label, description, default_on) values ('party', 'comment', '새 댓글', '내 글에 댓글이 달리면', true)");
        jdbc.update("insert into notify_types (app_id, type_id, label, description, default_on) values ('party', 'share', '지도 공유', '', false)");
        jdbc.update("insert into notify_types (app_id, type_id, label, description, default_on, deprecated) values ('party', 'old', '옛 종류', '', true, true)");
        jdbc.update("insert into notify_types (app_id, type_id, label, description, default_on) values ('blog', 'like', '좋아요', '', true)");
    }

    @Test
    @DisplayName("설정 행이 없으면 종류 등록부의 기본값이고, deprecated 종류는 보이지 않으며, 앱 이름과 라벨이 함께 온다")
    void viewMergesRegistry() throws Exception {
        perform(hub(get("/api/v1/notify/preferences"), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apps.length()").value(2))
                .andExpect(jsonPath("$.data.apps[?(@.app=='party')].displayName").value("도로 파티"))
                .andExpect(jsonPath("$.data.apps[?(@.app=='party')].types.length()").value(2))
                .andExpect(jsonPath("$.data.apps[?(@.app=='party')].types[?(@.type=='comment')].label").value("새 댓글"))
                .andExpect(jsonPath("$.data.apps[?(@.app=='party')].types[?(@.type=='comment')].enabled").value(true))
                .andExpect(jsonPath("$.data.apps[?(@.app=='party')].types[?(@.type=='share')].enabled").value(false))
                .andExpect(jsonPath("$.data.apps[?(@.app=='party')].types[?(@.type=='old')]").isEmpty());
    }

    @Test
    @DisplayName("변경은 허브·앱 경로 어디서든 같은 값을 읽고 쓰며, 다른 사용자에게는 보이지 않는다")
    void updateIsPerUserAndPathIndependent() throws Exception {
        perform(hub(put("/api/v1/notify/preferences").contentType(MediaType.APPLICATION_JSON)
                .content("{\"changes\":[{\"app\":\"party\",\"type\":\"comment\",\"enabled\":false},{\"app\":\"party\",\"type\":\"share\",\"enabled\":true}]}"), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apps[?(@.app=='party')].types[?(@.type=='comment')].enabled").value(false))
                .andExpect(jsonPath("$.data.apps[?(@.app=='party')].types[?(@.type=='share')].enabled").value(true));

        perform(app(get("/api/v1/notify/preferences"), alice, "party"))
                .andExpect(jsonPath("$.data.apps[?(@.app=='party')].types[?(@.type=='comment')].enabled").value(false));
        perform(hub(get("/api/v1/notify/preferences"), bob))
                .andExpect(jsonPath("$.data.apps[?(@.app=='party')].types[?(@.type=='comment')].enabled").value(true));
        assertThat(jdbc.queryForObject("select count(*) from notify_preferences where user_id = ?", Integer.class, bob)).isZero();
    }

    @Test
    @DisplayName("등록되지 않은 앱·종류가 하나라도 있으면 전체를 거절하고 아무것도 바꾸지 않는다")
    void rejectsUnknownAtomically() throws Exception {
        perform(hub(put("/api/v1/notify/preferences").contentType(MediaType.APPLICATION_JSON)
                .content("{\"changes\":[{\"app\":\"party\",\"type\":\"comment\",\"enabled\":false},{\"app\":\"party\",\"type\":\"nope\",\"enabled\":false}]}"), alice))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOTIFY-400-03"));
        assertThat(jdbc.queryForObject("select count(*) from notify_preferences", Integer.class)).isZero();
        for (String bad : new String[] {"{}", "{\"changes\":[]}", "{\"changes\":[{\"app\":\"party\",\"type\":\"comment\"}]}", "{\"changes\":[null]}"}) {
            perform(hub(put("/api/v1/notify/preferences").contentType(MediaType.APPLICATION_JSON).content(bad), alice)).andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("로그인하지 않으면 설정을 읽고 쓸 수 없다")
    void requiresLogin() throws Exception {
        perform(get("/api/v1/notify/preferences")).andExpect(status().isUnauthorized());
        perform(put("/api/v1/notify/preferences").contentType(MediaType.APPLICATION_JSON).content("{\"changes\":[]}")).andExpect(status().isUnauthorized());
    }
}
