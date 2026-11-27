-- 도로 면접(doro-interview)을 알림 앱으로 등록한다. 공개 주소는 개발자 제품(Doro Dev) 아래 /dev/interview/ 다.
-- 범위를 바꿀 일이 생기면 면접이 종류를 등록할 때 scopePath 로 알린다(InternalController.registerTypes).
INSERT INTO notify_apps (app_id, scope_path, display_name) VALUES
    ('interview', '/dev/interview/', '도로 면접')
ON CONFLICT (app_id) DO NOTHING;
