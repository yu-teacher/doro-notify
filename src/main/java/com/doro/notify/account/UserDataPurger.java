package com.doro.notify.account;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

import static com.doro.notify.common.db.Db.ts;

/**
 * 탈퇴한 사용자의 데이터를 지운다: 구독(대기 중인 발송 작업은 연쇄 삭제된다)·설정·요청 수신 기록. 사용자 사본 테이블은 없고
 * 탈퇴 표식(id 와 시각만)이 남는다. 표식은 탈퇴 직후 유효한 토큰으로 기기가 다시 등록되는 일을 막고, 주기 작업이 잔존 데이터를 쓸어 내는 기준이 된다.
 */
@Component
public class UserDataPurger {

    private final JdbcClient jdbc;
    private final Clock clock;

    UserDataPurger(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean isDeleted(UUID userId) {
        return jdbc.sql("SELECT 1 FROM notify_deleted_users WHERE user_id = :u").param("u", userId).query().listOfRows().size() > 0;
    }

    /** @return 이번 호출이 표식을 처음 남겼으면 true(이미 처리된 사용자는 false: 멱등) */
    @Transactional
    public boolean purge(UUID userId) {
        boolean first = jdbc.sql("INSERT INTO notify_deleted_users (user_id, deleted_at) VALUES (:u, :now) ON CONFLICT (user_id) DO NOTHING")
                .param("u", userId).param("now", ts(clock.instant())).update() > 0;
        deleteDataOf(userId);
        return first;
    }

    /** 동기화 처리 사이에 끼어든 잔존 구독·설정을 쓸어 낸다(주기 작업). 지운 구독 수를 돌려준다. */
    @Transactional
    public int sweepLeftovers() {
        int subscriptions = jdbc.sql("DELETE FROM push_subscriptions WHERE user_id IN (SELECT user_id FROM notify_deleted_users)").update();
        jdbc.sql("DELETE FROM notify_preferences WHERE user_id IN (SELECT user_id FROM notify_deleted_users)").update();
        jdbc.sql("DELETE FROM notify_request_recipients WHERE user_id IN (SELECT user_id FROM notify_deleted_users)").update();
        return subscriptions;
    }

    private void deleteDataOf(UUID userId) {
        jdbc.sql("DELETE FROM push_subscriptions WHERE user_id = :u").param("u", userId).update();
        jdbc.sql("DELETE FROM notify_preferences WHERE user_id = :u").param("u", userId).update();
        jdbc.sql("DELETE FROM notify_request_recipients WHERE user_id = :u").param("u", userId).update();
    }
}
