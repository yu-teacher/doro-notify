-- DORO Notify V1: 앱 등록부와 알림 종류 등록부.
-- 앱(생산자 서비스)은 이 마이그레이션이 시드로 등록한다. scope_path 는 알림을 눌렀을 때 열 수 있는 주소의 허용 범위다.
-- 앱이나 범위를 바꾸는 API 는 없다(변경은 새 마이그레이션): 호출자가 자기 앱의 범위를 넓혀 다른 앱 주소로 보내는 일을 막는다.

CREATE TABLE notify_apps (
    app_id       TEXT PRIMARY KEY,
    scope_path   TEXT NOT NULL,
    display_name TEXT NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT notify_apps_scope_shape CHECK (scope_path ~ '^/([A-Za-z0-9._~-]+/)*$')
);

INSERT INTO notify_apps (app_id, scope_path, display_name) VALUES
    ('party', '/party/', '도로 파티'),
    ('blog',  '/blog/',  '도로 블로그'),
    ('games', '/games/', '도로 게임 천국'),
    ('menu',  '/menu/',  '도로 메뉴'),
    ('hub',   '/',       '도로 허브');

-- 알림 종류(앱이 기동할 때 PUT /internal/v1/apps/{app}/types 로 등록한다). 지우지 않고 deprecated 로 표시한다.
CREATE TABLE notify_types (
    app_id      TEXT NOT NULL REFERENCES notify_apps (app_id),
    type_id     TEXT NOT NULL,
    label       TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    default_on  BOOLEAN NOT NULL DEFAULT TRUE,
    deprecated  BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (app_id, type_id)
);
