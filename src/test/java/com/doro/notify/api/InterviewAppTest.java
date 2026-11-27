package com.doro.notify.api;

import com.doro.notify.support.AbstractNotifyTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 도로 면접(interview)이 알림 앱으로 등록돼 있다: 자기 종류를 등록하고, 자기 공개 경로(/dev/interview/) 안의 주소로만 보낼 수 있다. */
class InterviewAppTest extends AbstractNotifyTest {

    static String request(String key, UUID user, String url) {
        return "{\"idempotencyKey\":\"" + key + "\",\"app\":\"interview\",\"type\":\"review\",\"recipients\":[\"" + user + "\"],"
                + "\"title\":\"복습할 질문이 있어요\",\"body\":\"3개\",\"tag\":\"interview-review\",\"url\":\"" + url + "\"}";
    }

    @Test
    @DisplayName("면접은 복습 알림 종류를 등록하고 /dev/interview/ 아래 주소로 알림을 요청할 수 있다")
    void registersAndSendsInsideScope() throws Exception {
        perform(internal(put("/internal/v1/apps/interview/types"), INTERVIEW_TOKEN)
                .content("{\"types\":[{\"type\":\"review\",\"label\":\"복습 알림\"}],\"scopePath\":\"/dev/interview/\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registered").value(1));

        UUID user = UUID.randomUUID();
        subscribe(user, "interview", "device-iiiiiiii");
        perform(internal(post("/internal/v1/notifications"), INTERVIEW_TOKEN).content(request("review:1", user, "/dev/interview/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.accepted").value(1));
    }

    @Test
    @DisplayName("면접은 자기 범위 밖(다른 앱) 주소로는 보낼 수 없다")
    void rejectsUrlOutsideScope() throws Exception {
        registerType("interview", "review", true);
        perform(internal(post("/internal/v1/notifications"), INTERVIEW_TOKEN).content(request("review:2", UUID.randomUUID(), "/party/")))
                .andExpect(status().isBadRequest());
    }
}
