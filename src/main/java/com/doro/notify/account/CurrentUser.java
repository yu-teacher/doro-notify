package com.doro.notify.account;

import com.doro.notify.common.exception.ErrorCode;
import com.doro.notify.common.exception.NotifyException;
import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.domain.DoroUserContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 지금 요청을 보낸 사람(Doro 사용자 id). SDK 의 JWT 필터가 요청 스레드에 올려 둔 사용자를 읽는다. 사용자 id 는 **토큰에서만** 얻는다:
 * 요청 경로·쿼리·본문으로 받은 id 를 믿는 코드가 없어서 남의 기기·설정에는 닿을 방법이 없다. 잘못된·만료된 토큰은 필터가 익명으로 취급한다.
 * 탈퇴 표식이 있는 사용자는 아직 유효한 토큰을 들고 있어도 익명과 같다(탈퇴 직후 구독·설정이 다시 생기지 않게).
 */
@Component
public class CurrentUser {

    private final UserDataPurger purger;

    CurrentUser(UserDataPurger purger) {
        this.purger = purger;
    }

    /** 로그인한 사용자 id. 로그인하지 않았거나 탈퇴한 사용자면 401. */
    public UUID require() {
        DoroUser user = DoroUserContext.getCurrentUser();
        if (user == null || !user.isAuthenticated() || purger.isDeleted(user.userId())) {
            throw new NotifyException(ErrorCode.UNAUTHORIZED);
        }
        return user.userId();
    }
}
