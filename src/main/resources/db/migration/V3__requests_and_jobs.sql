-- DORO Notify V3: 요청 기록(멱등 키)과 발송 큐.
-- 알림 제목·본문은 요청 기록에 저장하지 않는다. 발송 큐의 payload 에만 있고, 발송이 끝나면(성공·실패·버림) NULL 로 비운다.

-- 요청 단위. (caller, idempotency_key) 가 같으면 같은 요청이다.
-- 설계 문서의 notify_requests 는 recipient_user_id 를 한 행에 두지만 요청 하나에 수신자가 여럿이라, 수신자별 판정은 notify_request_recipients 로 나눴다.
CREATE TABLE notify_requests (
    id              UUID PRIMARY KEY,
    caller          TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    app_id          TEXT NOT NULL,
    type_id         TEXT NOT NULL,
    accepted        INT NOT NULL DEFAULT 0,       -- 수신자 수: 보낼 기기가 있고 설정이 켜진 사람
    suppressed      INT NOT NULL DEFAULT 0,       -- 수신자 수: 앱·종류를 꺼 둔 사람
    no_subscription INT NOT NULL DEFAULT 0,       -- 수신자 수: 보낼 기기가 없는 사람
    devices         INT NOT NULL DEFAULT 0,       -- 큐에 넣은 구독(기기) 수
    trace_id        TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT notify_requests_idem UNIQUE (caller, idempotency_key)
);
CREATE INDEX idx_notify_requests_created ON notify_requests (created_at);

CREATE TABLE notify_request_recipients (
    request_id UUID NOT NULL REFERENCES notify_requests (id) ON DELETE CASCADE,
    user_id    UUID NOT NULL,
    outcome    TEXT NOT NULL CHECK (outcome IN ('ACCEPTED', 'SUPPRESSED_PREF', 'NO_SUBSCRIPTION')),
    PRIMARY KEY (request_id, user_id)
);
CREATE INDEX idx_notify_request_recipients_user ON notify_request_recipients (user_id);

-- 구독(기기) 단위 발송 큐.
CREATE TABLE delivery_jobs (
    id              UUID PRIMARY KEY,
    request_id      UUID NOT NULL REFERENCES notify_requests (id) ON DELETE CASCADE,
    subscription_id UUID NOT NULL REFERENCES push_subscriptions (id) ON DELETE CASCADE,
    app_id          TEXT NOT NULL,
    tag             TEXT,
    topic           TEXT NOT NULL,                -- 푸시 서비스에서 대기 중인 같은 주제의 알림을 합치는 이름의 원본(태그 또는 작업 id)
    urgent          BOOLEAN NOT NULL DEFAULT FALSE,
    payload         BYTEA,                        -- 암호화 전 페이로드(계약 v1 JSON). 발송이 끝나면 NULL
    status          TEXT NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'DROPPED')),
    attempts        INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    last_status     INT,                          -- 푸시 서비스의 마지막 HTTP 상태(0 = 연결 실패)
    trace_id        TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_delivery_jobs_due ON delivery_jobs (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_delivery_jobs_subscription_tag ON delivery_jobs (subscription_id, tag) WHERE status = 'PENDING';
CREATE INDEX idx_delivery_jobs_request ON delivery_jobs (request_id);
CREATE INDEX idx_delivery_jobs_updated ON delivery_jobs (updated_at);
