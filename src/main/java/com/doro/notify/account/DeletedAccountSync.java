package com.doro.notify.account;

import com.hunnit_beasts.doro.sdk.sync.DeletedUserHandler;
import com.hunnit_beasts.doro.sdk.sync.DeletedUsersProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Doro 의 영구 탈퇴 사용자 목록을 주기적으로 가져와 이 서비스의 구독·설정을 지운다. 가져오기·재시도·진행 위치 관리는 SDK 가 하고
 * (아래 세 빈이 있으면 켜진다), 이 서비스는 "한 사용자를 어떻게 정리하는가" 만 정한다. 설정은 {@code notify.deleted-sync.*},
 * IAM 주소·서비스 토큰은 {@code doro.iam.internal-url/-token}(토큰은 환경변수로만).
 */
@Configuration
class DeletedAccountSync {

    @Bean
    @ConfigurationProperties(prefix = "notify.deleted-sync")
    DeletedUsersProperties deletedUsersProperties() {
        return new DeletedUsersProperties();
    }

    @Bean
    DeletedUserHandler deletedUserHandler(UserDataPurger purger) {
        return new Handler(purger);
    }

    /** 한 사용자의 정리. 표식과 삭제가 한 트랜잭션이라 중간에 실패하면 다음 주기에 같은 사용자부터 다시 처리된다. 이미 정리된 사용자는 false(멱등). */
    static class Handler implements DeletedUserHandler {

        private final UserDataPurger purger;

        Handler(UserDataPurger purger) {
            this.purger = purger;
        }

        @Override
        @Transactional
        public boolean onDeleted(UUID userId, Instant deletedAt) {
            return purger.purge(userId);
        }
    }
}
