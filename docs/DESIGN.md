# 도로 알림 서비스 설계 요약

플랫폼 알림 서비스(doro-notify)가 **무엇을 소유하고, 어떻게 동작하며, 무엇을 전제로 하는지**를 한곳에 모은 문서. 코드와 어긋나면 코드와 테스트가 기준이다.

## 1. 경계

**이 서비스가 소유**: 기기(웹 푸시 구독), 앱별·종류별 알림 설정, 알림 종류 등록부, 발송 큐와 결과, 멱등 키.
**각 서비스(요청하는 쪽)가 소유**: 수신자 계산, 수신자 권한 확인, 문구, 눌렀을 때 주소, "알릴지"의 업무 규칙. 이 서비스는 그것들을 모르고 Guard 도 쓰지 않는다(`doro.guard.enabled=false`).

이 서비스가 하는 판단은 세 가지뿐이다: (a) 수신자가 이 앱·종류를 껐는가, (b) 같은 요청이 이미 왔는가, (c) 보낼 기기가 있는가.

사용자 사본 테이블은 없다. 사용자 id 는 토큰(JWT)의 `sub` 에서만 얻고, 서비스 사이는 id 로만 참조한다. 세션 컨트롤러(`/api/v1/bff/session`)도 없다.

## 2. 데이터 모델 (Flyway, 이력 테이블 `notify_schema_history`)

| 테이블 | 내용 |
| --- | --- |
| `notify_apps` | 앱 등록부: `app_id`, `scope_path`(알림 주소 허용 범위), 표시 이름. 마이그레이션 시드(party·blog·games·menu·hub) |
| `notify_types` | 알림 종류: 라벨·설명·기본값·`deprecated`. 앱이 기동 때 내부 API 로 등록 |
| `push_subscriptions` | 구독: 사용자·앱·`device_id`·`endpoint`(UNIQUE)·`p256dh`·`auth`·연속 거절 횟수 |
| `notify_preferences` | `(사용자, 앱, 종류)` → 켜짐/꺼짐. 행이 없으면 종류의 기본값 |
| `notify_requests` | 요청 단위 기록과 멱등 키 `UNIQUE(caller, idempotency_key)`, 판정 수치 |
| `notify_request_recipients` | 요청의 수신자별 판정(`ACCEPTED`/`SUPPRESSED_PREF`/`NO_SUBSCRIPTION`) |
| `delivery_jobs` | 구독 단위 발송 큐. 암호화 전 페이로드는 **발송이 끝나면 NULL 로 비운다** |
| `notify_deleted_users` | 탈퇴 표식(id·시각만) |
| `iam_sync_state` | 탈퇴 동기화 진행 위치 |

- 알림 제목·본문은 요청 기록에 저장하지 않는다. 큐에 있는 동안만 두고, 끝나면 비운다. 로그에도 남기지 않는다.
- **앱 등록부는 마이그레이션 시드로 둔다.** 블로그의 `/blog/` 범위도 시드다(설정으로 풀면 호출자가 자기 범위를 넓힐 수 있다). 범위·앱을 바꾸려면 새 마이그레이션을 추가한다.
- `notify_requests` 는 요청 하나(수신자 여럿)당 한 행이고 수신자별 판정은 별도 테이블이다(요청 단위 멱등과 수신자별 판정을 함께 담기 위한 구조).

## 3. 클라이언트 API (`/api/v1/notify`, Bearer JWT)

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/config` | VAPID 공개 키와 사용 가능 여부(로그인 없이) |
| PUT | `/subscriptions` | 구독 등록(upsert). 앱 경로에서만. 본문 `deviceId`, `endpoint`, `keys{p256dh,auth}` |
| DELETE | `/subscriptions` | 구독 해제(본문 `endpoint`). 내 것만, 없어도 성공 |
| DELETE | `/devices/{deviceId}` | 이 기기의 내 모든 앱 구독 해제(로그아웃) |
| GET | `/devices` | 내 기기 목록(기기별 묶음, 앱별 상태). endpoint·키·원문 User-Agent 는 주지 않는다 |
| GET / PUT | `/preferences` | 앱·종류별 설정(종류 등록부와 합쳐 라벨 포함) / 변경(원자적) |
| POST | `/test` | 내 기기로 시험 알림(앱 경로면 그 앱만, 허브 경로면 전부) |

- (사용자, 앱)당 구독 상한 10개(넘으면 가장 오래된 것부터 지운다). 같은 endpoint 가 다른 계정으로 오면 **소유가 옮겨지고** 이전 소유자 앞 대기 작업은 버려진다. 해제·설정은 인증된 사용자 소유 행에만 적용된다.
- 탈퇴 표식이 있는 사용자는 아직 유효한 토큰이 있어도 401 이다.

### 앱 식별과 신뢰 전제

호출한 **앱은 본문이 아니라 헤더 `X-Doro-Notify-App` 으로만** 정해진다. 구독 등록은 이 헤더가 있을 때만 받고(없으면 403), 본문에 `app` 이 오면 헤더와 같을 때만 받는다(다르면 403). 헤더 값이 등록된 앱이 아니면 400.

경로는 두 가지다.
- **앱 경로**: 게이트웨이가 앱 접두사 아래(`/<앱>/api/v1/notify/…` → 이 서비스의 `/api/v1/notify/…`)를 이 서비스로 넘기면서 앱별 location 에서 헤더를 붙인다.
- **허브 경로**: `/notify/api/v1/…` → `/api/v1/notify/…`, Bearer 직접, 헤더 없음. 설정 조회·변경, 기기 목록·해제, 시험 알림만 쓰고 구독 등록은 못 한다.

**이 서비스의 로컬 보호는 "게이트웨이가 항상 이 헤더를 덮어쓴다"는 전제 위에 있다.** 즉 (1) 앱 location 은 클라이언트가 보낸 값을 버리고 자기 앱 id 를 `proxy_set_header` 로 넣고, (2) 허브 경로와 그 밖의 모든 경로는 헤더를 빈 값으로 지워서 넘기며, (3) 이 서비스의 포트는 게이트웨이와 내부 네트워크에서만 닿는다. 이 전제가 깨지면(게이트웨이를 거치지 않고 직접 닿는 경로가 생기면) 클라이언트가 다른 앱 행세로 구독을 등록할 수 있다.

받아 줄 토큰: 앱 BFF 가 받은 토큰(`cid` 가 `NOTIFY_OAUTH_CLIENT_IDS` 목록에 있는 것)과 허브의 IAM 액세스 토큰(`cid` 없음). 알림을 쓰는 앱의 클라이언트 id 를 이 목록에 맞춘다.

## 4. 내부 API (`/internal/v1`)

게이트웨이가 라우팅하지 않는다. 호출자 서비스 토큰 헤더 `X-Doro-Service-Token` 으로 호출자를 식별한다(IAM `InternalApiAuthFilter` 와 같은 방식: `이름:토큰` 목록, 토큰 32자 이상, 모드 OFF/WARN/**ENFORCE**(기본), `X-Forwarded-For`/`X-Real-IP` 가 붙은 요청은 404, 토큰 값은 로그에 남기지 않는다). **호출자는 토큰 이름과 같은 `app` 으로만 요청할 수 있다**(다르면 403).

- `POST /notifications` — 알림 요청. 본문: `idempotencyKey`, `app`, `type`, `recipients`, `title`, `body`, `tag`, `url`, `icon`(선택), `ttlSeconds`, `urgency`(normal|high), `expiresAt`, `dryRun`.
  - 응답은 큐 적재 기준이다: `{outcome, accepted, suppressed, noSubscription, devices}`(수신자 수 기준, `devices` 는 큐에 넣은 구독 수). 전송은 비동기.
  - `outcome`: `ACCEPTED` · `DUPLICATE`(같은 (호출자, 키) 재요청: 200, 처음 응답의 수치) · `EXPIRED`(`expiresAt` 이 이미 지남: 200, 저장 없음) · `DRY_RUN`(판정만, 저장 없음, 멱등 키도 쓰지 않음).
  - 검증: `url`(과 `icon`)은 호출 앱의 `scope_path` 안의 같은 출처 경로여야 한다(절대 주소·`//`·`..`·역슬래시·퍼센트 인코딩 우회는 400). 수신자 최대 500명, 제목 120자·본문 500자·tag 128자·url 1024자, `ttlSeconds` 1초~7일(기본 24시간), 암호화 전 페이로드가 약 4KB 를 넘으면 400.
  - 요청 기록·수신자 판정·발송 작업은 **한 트랜잭션**이다. 같은 구독에 같은 `tag` 로 대기 중인 옛 작업은 새 알림이 대신한다.
- `PUT /apps/{app}/types` — 종류 등록(멱등 upsert). 보낸 목록이 현재 쓰는 종류 전체다: 빠진 종류는 지우지 않고 `deprecated` 로 표시한다(사용자 설정 보존).
- `GET /stats` — 운영 점검: 발송 작업의 상태별 건수, 가장 오래 기다린 PENDING 의 나이(초). 알림 내용은 없다.

## 5. 페이로드 계약 v1 (기기로 가는 푸시 JSON)

```json
{ "v": 1, "app": "party", "type": "comment", "title": "…", "body": "…", "tag": "…", "url": "/party/?…", "icon": "…" }
```
키 순서 고정, `tag`·`icon` 은 없으면 키를 싣지 않는다. 알 수 없는 `v` 는 서비스 워커가 무시하지 않고 `title`/`body` 로 표시한다. `PayloadContractTest` 가 이 모양을 고정한다.

## 6. 발송 워커와 재시도 정책

`FOR UPDATE SKIP LOCKED` 로 보낼 차례인 작업을 **임대**(다음 시도 시각을 2분 뒤로 밀어 둠)해 가져오고, 푸시 서비스 호출 전용 스레드 풀(기본 4)에서 보낸다. 처리 도중 프로세스가 죽으면 임대가 끝난 뒤 다시 가져간다.

| 푸시 서비스 응답 | 처리 |
| --- | --- |
| 2xx | `SENT`, 연속 거절 횟수 0 |
| 404 / 410 | 구독 삭제(작업도 함께 사라진다) |
| 400·401·403·413 등 거절 | 재시도하지 않고 `FAILED`, 연속 거절 +1, **5번 연속이면 구독 삭제** |
| 시간 초과·연결 실패·429·5xx | 일시 오류: 지수 백오프 재시도. **구독은 절대 지우지 않는다** |

- 재시도: 첫 재시도 30초, 두 배씩 늘어 최대 30분, ±20% 흩뿌림, **총 8번(첫 시도 포함)**. 다음 시도가 작업의 만료 시각(`min(요청 시각+ttl, expiresAt)`) 이후면 바로 `DROPPED`. 8번 모두 실패하면 `FAILED`. 보내기 전에 만료된 작업은 보내지 않고 `DROPPED`.
- 보낼 때마다 저장된 endpoint 를 허용 목록으로 다시 검증한다(리디렉션은 따르지 않는다). 푸시 서비스에는 `TTL`(남은 시간)·`Urgency`·`Topic`(tag 의 해시)을 보낸다.
- 끝난 작업(SENT·FAILED·DROPPED)의 페이로드는 비운다.

## 7. 보안과 로그

- **구독 주소 허용 목록(SSRF)**: 알려진 푸시 서비스 도메인의 https·기본 포트만. IP·계정 정보·허용 밖 도메인·비 ASCII 거절. p256dh 는 곡선 위의 점인지, auth 는 16바이트인지 확인한다.
- **내 것만**: 모든 해제·설정은 토큰의 사용자 id 조건을 함께 건다.
- **로그·응답·오류에 비밀 금지**: endpoint·p256dh·auth·VAPID 개인 키·알림 본문·호출자 토큰은 남기지 않는다(푸시 서비스의 호스트와 상태 코드만). 구독·작업·요청 객체의 `toString` 도 비밀을 싣지 않는다. 시험(`ObservabilityTest`)이 등록·발송·실패·잘못된 입력을 지나며 로그와 응답을 검사한다.
- **traceId**: SDK 필터가 `X-Trace-Id` 를 MDC 에 넣고, 요청 기록·작업에 저장해 워커 로그까지 같은 id 로 이어진다(로그 패턴에 `[traceId=…]`). 로그는 stdout 으로만 낸다.
- 응답은 `Cache-Control: no-store`, 요청 본문은 클라이언트 16KB·내부 256KB 상한.

## 8. 탈퇴

SDK `DeletedUserHandler` 로 사용자의 구독·설정·수신 기록을 지우고 탈퇴 표식(id·시각)을 남긴다(멱등, 한 트랜잭션). 표식은 아직 유효한 토큰으로 기기가 다시 등록되는 일을 막는다. 주기 작업이 동기화 사이에 끼어든 잔존 구독·설정도 쓸어 낸다.

## 9. 배포와 운영

- 서비스 이름 `doro-notify`, 패키지 `com.doro.notify`, 슬러그 `notify`, api 포트 8089, DB `service_notify`, 서버 계정 `notify_app`, 컨테이너 `doro-notify-api`.
- 이 저장소가 주는 것: `Dockerfile`(멀티스테이지·비루트·헬스체크), `docker-compose.prod.yml`(참조 사본: 서버 파일이 기준), `scripts/setup-notify-server.sh`(DB·`.env`·VAPID 준비, 기본 dry-run), `scripts/deploy-on-server.sh`(롤백 태그·헬스·게이트웨이 reload·경유 확인), CI(`.github/workflows/ci.yml`)·CD(`deploy.yml`, 저장소 변수 `CD_ENABLED=true` 일 때만).
- **Doro 쪽에서 할 일(이 저장소 밖)**: 게이트웨이 location(앱 접두사 아래와 허브 `/notify/api/v1/`)과 `X-Doro-Notify-App` 헤더 주입·제거, 앱 BFF 클라이언트 id 맞춤, 내부 호출자 토큰 발급, DB 계정 분리(`split-db-accounts.sh`)·탈퇴 동기화 토큰(`enable-deleted-sync.sh`)에 notify 추가, 허브 알림 화면.
- **수동 롤백**: 배포 스크립트가 이전 이미지에 `rollback-<시각>` 태그를 붙여 둔다. 실패하면 스크립트가 되돌리고, 수동으로는 `docker tag doro-notify-api:rollback-<시각> doro-notify-api:latest` 뒤 `docker compose -p notify -f docker-compose.prod.yml up -d --no-build --force-recreate notify-api`. DB 마이그레이션은 되돌려지지 않으므로 추가 위주로 쓴다.
- **VAPID 키**: 파티가 쓰던 키를 그대로 쓰면 이미 등록된 기기가 재구독 없이 이어진다. 키를 바꾸면 모든 구독이 무효다. 키가 없으면 푸시만 꺼지고 서비스는 정상 기동한다.
- 운영 점검: `GET /internal/v1/stats`(PENDING 이 오래 쌓이면 워커·푸시 서비스·VAPID 설정을 본다), 로그의 `Delivery failed (retries exhausted)`·`Push subscription removed`.
