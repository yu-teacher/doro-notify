-- DORO Notify V2: 기기 구독과 알림 설정.
-- endpoint·p256dh·auth 는 서버가 대신 접속하고 암호화하는 데 쓰는 비밀이다: 로그·응답·오류에 남기지 않는다.

CREATE TABLE push_subscriptions (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL,                 -- IAM 사용자 ID(사본 테이블 없음, 토큰의 sub 만 쓴다)
    app_id          TEXT NOT NULL REFERENCES notify_apps (app_id),   -- 이 구독을 만든 서비스 워커의 앱
    device_id       TEXT NOT NULL,                 -- 클라이언트가 만든 기기 표식(표시용 그룹핑)
    endpoint        TEXT NOT NULL UNIQUE,
    p256dh          TEXT NOT NULL,
    auth            TEXT NOT NULL,
    user_agent      TEXT,
    fail_count      INT NOT NULL DEFAULT 0,        -- 거절 응답 연속 횟수(성공하면 0)
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    last_success_at TIMESTAMP WITH TIME ZONE,
    last_seen_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT push_subscriptions_endpoint_len CHECK (char_length(endpoint) <= 2048)
);
CREATE INDEX idx_push_subscriptions_user_app ON push_subscriptions (user_id, app_id, created_at);
CREATE INDEX idx_push_subscriptions_user_device ON push_subscriptions (user_id, device_id);

-- 행이 없으면 종류 등록부의 default_on 을 따른다.
CREATE TABLE notify_preferences (
    user_id    UUID NOT NULL,
    app_id     TEXT NOT NULL,
    type_id    TEXT NOT NULL,
    enabled    BOOLEAN NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, app_id, type_id)
);
