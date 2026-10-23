-- Doro 의 "영구 탈퇴한 사용자" 목록을 어디까지 처리했는지(증분 조회의 기준 시각). SDK 동기화가 쓴다.
CREATE TABLE iam_sync_state (
    name       VARCHAR(50) PRIMARY KEY,
    cursor_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 탈퇴 표식: 사용자 id 만 둔다(사본이 아니다). 두 가지에 쓴다.
--  1) 탈퇴 직후 아직 유효한 토큰으로 구독·설정을 다시 만드는 일을 막는다.
--  2) 동기화 처리 사이에 끼어든 잔존 구독·설정을 주기 작업이 쓸어 낸다.
CREATE TABLE notify_deleted_users (
    user_id    UUID PRIMARY KEY,
    deleted_at TIMESTAMP WITH TIME ZONE NOT NULL
);
