# 도로 알림 (doro-notify)

[Doro](https://github.com/yu-teacher) 플랫폼의 알림 서비스 백엔드. 기기(웹 푸시 구독) 등록, 앱별·종류별 알림 설정, 발송을 한곳에서 맡는다. 알림을 보내려는 다른 서비스(파티·블로그 등)는 내부 API 로 요청만 하면 되고, 사용자는 허브 "내 계정"에서 앱별 설정과 내 기기 목록을 본다.

- 설계 요약과 운영 규칙: [docs/DESIGN.md](docs/DESIGN.md)
- 웹 컨테이너는 없다(화면은 허브와 각 앱이 맡는다). 백엔드 API 컨테이너 하나(포트 8089)다.

## 구조
- `src/main/java/com/doro/notify/`
  - `push/` 웹 푸시 전송 계층: `WebPushEncryptor`(RFC 8291 메시지 암호화), `VapidSigner`(RFC 8292), `WebPushClient`, `PushEndpointPolicy`(구독 주소 허용 목록·SSRF 방어)
  - `subscription/` 기기 구독 · `preference/` 설정 · `registry/` 앱·알림 종류 등록부
  - `request/` 서비스 간 알림 요청(멱등·수신자별 판정) · `delivery/` 발송 큐·워커·재시도·정리 작업·페이로드 계약 v1
  - `internal/` 내부 API 호출자 토큰 필터 · `api/` 컨트롤러(클라이언트용 `/api/v1/notify`, 내부용 `/internal/v1`)
  - `account/` 현재 사용자와 탈퇴 동기화
- `src/main/resources/db/migration/` Flyway 마이그레이션(이력 테이블 `notify_schema_history`)
- `scripts/` CI·서버 준비·배포 스크립트

## 로컬 검증
```bash
scripts/ci-test.sh backend   # 격리 Postgres(기본 포트 25434)를 올려 백엔드 테스트 후 내린다
```
필요: Docker, Java 25, 형제 디렉터리의 Doro 체크아웃(`../Doro`, SDK 를 쓴다). 다른 실행과 겹치면 `CI_PROJECT`·`CI_DB_PORT` 를 바꾼다.

실제 푸시 서비스(FCM 등)와의 호환 확인(`PushLiveInteropTest`)은 일반 시험에서 건너뛴다. 실제 브라우저의 구독 파일과 VAPID 키 파일을 한 폴더에 두고 `PUSH_LIVE_DIR=<폴더> ./gradlew test --tests '*PushLiveInteropTest'` 로 돌린다.

## 설정
모든 설정은 환경변수로 바꾼다(`src/main/resources/application.yaml` 의 `${ENV:기본값}`). 비밀은 저장소에 두지 않는다.

| 환경변수 | 뜻 |
| --- | --- |
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USER` `DB_PASSWORD` | Postgres 접속 |
| `IAM_HOST` `IAM_PORT` | Doro IAM(토큰 검증용 JWKS) |
| `NOTIFY_INTERNAL_TOKENS` | 내부 API 호출자 토큰 `이름:토큰,…`(이름 = 앱 id) |
| `NOTIFY_VAPID_PUBLIC_KEY` `NOTIFY_VAPID_PRIVATE_KEY` `NOTIFY_VAPID_SUBJECT` | 웹 푸시 키(없으면 푸시만 꺼진다) |
| `DORO_IAM_INTERNAL_TOKEN` | 탈퇴 동기화용 IAM 내부 API 토큰 |

나머지(재시도·보관 기간·상한 등)는 `application.yaml` 의 `notify.*` 를 본다.
