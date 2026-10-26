#!/usr/bin/env bash
# 서버(mini)에서 실행한다: 파티의 푸시 구독·알림 설정을 알림 서비스로 가져온다(이관 P0/SHADOW 준비). 여러 번 실행해도 결과가 같다(멱등). 값은 화면에 출력하지 않는다.
#
#   ssh mini 'bash -s -- --dry-run' < scripts/import-party-subscriptions.sh   # 읽기 전용: 개수만 비교(기본)
#   ssh mini 'bash -s -- --apply'   < scripts/import-party-subscriptions.sh   # 가져오기 반영
#
# 하는 일
#   1) service_party 의 push_subscriptions → service_notify 의 push_subscriptions(app_id='party', device_id='legacy-<id 앞 8자>').
#      같은 endpoint 는 갱신하고(키·소유자), 파티에서 사라진 구독은 알림 서비스의 'legacy-' 가져오기 행 중에서만 지운다(이관 중 파티에서 해제한 기기가 남지 않게).
#      같은 VAPID 키라 이미 등록된 기기가 재구독 없이 이어진다.
#   2) push_preferences(comments·friend_requests·shares) → notify_preferences(app 'party', 종류 comment·friend·share). 파티가 기준이다: 가져올 때마다 덮어쓴다.
# 하지 않는 일: 파티 DB 변경(읽기만 한다), 알림 서비스의 'legacy-' 가 아닌 구독(앞으로 알림 서비스에서 직접 등록된 기기) 변경, 컨테이너 재시작.
# 구독·키·endpoint 는 psql 파이프로 컨테이너 안에서만 오가고 파일이나 화면에 남지 않는다. 임시 스테이징 테이블은 끝나면 지운다.
# 환경변수(리허설·점검용, 기본값은 운영): POSTGRES_CONTAINER, PARTY_DB, NOTIFY_DB
set -Eeuo pipefail

POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-doro-postgres}"
PARTY_DB="${PARTY_DB:-service_party}"
NOTIFY_DB="${NOTIFY_DB:-service_notify}"
APPLY=false
for a in "$@"; do
  case "$a" in
    --apply) APPLY=true ;;
    --dry-run) APPLY=false ;;
    *) echo "사용법: bash -s -- --dry-run | --apply" >&2; exit 2 ;;
  esac
done
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }
psql_in() { local db="$1"; shift; docker exec -i "$POSTGRES_CONTAINER" sh -c "psql -U \"\$POSTGRES_USER\" -d $db -v ON_ERROR_STOP=1 -tA $*"; }
scalar() { echo "$2" | psql_in "$1"; }

docker inspect "$POSTGRES_CONTAINER" >/dev/null 2>&1 || die "$POSTGRES_CONTAINER 컨테이너가 없다"
[ "$(scalar postgres "SELECT 1 FROM pg_database WHERE datname='$PARTY_DB'")" = 1 ] || die "$PARTY_DB 가 없다"
[ "$(scalar postgres "SELECT 1 FROM pg_database WHERE datname='$NOTIFY_DB'")" = 1 ] || die "$NOTIFY_DB 가 없다"

P_SUBS="$(scalar "$PARTY_DB" "SELECT count(*) FROM push_subscriptions")"
P_PREFS="$(scalar "$PARTY_DB" "SELECT count(*) FROM push_preferences")"
N_LEGACY="$(scalar "$NOTIFY_DB" "SELECT count(*) FROM push_subscriptions WHERE app_id='party' AND device_id LIKE 'legacy-%'")"
N_OTHER="$(scalar "$NOTIFY_DB" "SELECT count(*) FROM push_subscriptions WHERE app_id='party' AND device_id NOT LIKE 'legacy-%'")"
log "파티: 구독 $P_SUBS, 설정 행 $P_PREFS | 알림 서비스: 가져온 구독 $N_LEGACY, 알림 서비스에서 직접 등록된 파티 구독 $N_OTHER"
if [ "$APPLY" != true ]; then log "--dry-run: 여기서 종료 (--apply 로 반영)"; exit 0; fi

# 스테이징 테이블(실제 테이블: 두 DB 사이를 한 psql 세션으로 못 잇는다). 실패해도 지운다.
cleanup() { echo "DROP TABLE IF EXISTS notify_import_subs, notify_import_prefs" | psql_in "$NOTIFY_DB" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cat <<'SQL' | psql_in "$NOTIFY_DB" >/dev/null
DROP TABLE IF EXISTS notify_import_subs, notify_import_prefs;
CREATE TABLE notify_import_subs (id uuid, user_id uuid, endpoint text, p256dh text, auth text, user_agent text, created_at timestamptz, last_success_at timestamptz, failure_count int);
CREATE TABLE notify_import_prefs (user_id uuid, comments boolean, friend_requests boolean, shares boolean);
SQL

docker exec -i "$POSTGRES_CONTAINER" sh -c "psql -U \"\$POSTGRES_USER\" -d $PARTY_DB -v ON_ERROR_STOP=1 -c \"\\copy (SELECT id, user_id, endpoint, p256dh, auth, user_agent, created_at, last_success_at, failure_count FROM push_subscriptions) TO STDOUT WITH CSV\"" </dev/null \
  | docker exec -i "$POSTGRES_CONTAINER" sh -c "psql -U \"\$POSTGRES_USER\" -d $NOTIFY_DB -v ON_ERROR_STOP=1 -c \"\\copy notify_import_subs FROM STDIN WITH CSV\"" >/dev/null
docker exec -i "$POSTGRES_CONTAINER" sh -c "psql -U \"\$POSTGRES_USER\" -d $PARTY_DB -v ON_ERROR_STOP=1 -c \"\\copy (SELECT user_id, comments, friend_requests, shares FROM push_preferences) TO STDOUT WITH CSV\"" </dev/null \
  | docker exec -i "$POSTGRES_CONTAINER" sh -c "psql -U \"\$POSTGRES_USER\" -d $NOTIFY_DB -v ON_ERROR_STOP=1 -c \"\\copy notify_import_prefs FROM STDIN WITH CSV\"" >/dev/null

cat <<'SQL' | psql_in "$NOTIFY_DB" >/dev/null
BEGIN;
-- 1) 구독: 같은 endpoint 는 갱신(소유자·키), 새 것은 추가. 알림 서비스가 직접 받은 구독(legacy- 아님)과 endpoint 가 겹치면 그쪽을 존중해 건너뛴다.
INSERT INTO push_subscriptions (id, user_id, app_id, device_id, endpoint, p256dh, auth, user_agent, fail_count, created_at, last_success_at, last_seen_at)
SELECT s.id, s.user_id, 'party', 'legacy-' || left(s.id::text, 8), s.endpoint, s.p256dh, s.auth, s.user_agent, s.failure_count, s.created_at, s.last_success_at, COALESCE(s.last_success_at, s.created_at)
FROM notify_import_subs s
ON CONFLICT (endpoint) DO UPDATE
   SET user_id = EXCLUDED.user_id, p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth, user_agent = EXCLUDED.user_agent,
       fail_count = EXCLUDED.fail_count, last_success_at = EXCLUDED.last_success_at
 WHERE push_subscriptions.app_id = 'party' AND push_subscriptions.device_id LIKE 'legacy-%';
-- 파티에서 사라진 구독은 가져오기 행에서만 지운다.
DELETE FROM push_subscriptions
 WHERE app_id = 'party' AND device_id LIKE 'legacy-%'
   AND endpoint NOT IN (SELECT endpoint FROM notify_import_subs);
-- 2) 설정: 파티가 기준이다. 세 종류 모두 덮어쓴다(행이 없던 사용자는 기본값=켜짐이므로 행을 만들지 않는다).
INSERT INTO notify_preferences (user_id, app_id, type_id, enabled, updated_at)
SELECT p.user_id, 'party', t.type_id, t.enabled, now()
FROM notify_import_prefs p
CROSS JOIN LATERAL (VALUES ('comment', p.comments), ('friend', p.friend_requests), ('share', p.shares)) AS t(type_id, enabled)
ON CONFLICT (user_id, app_id, type_id) DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = now();
COMMIT;
SQL

N_AFTER="$(scalar "$NOTIFY_DB" "SELECT count(*) FROM push_subscriptions WHERE app_id='party' AND device_id LIKE 'legacy-%'")"
log "가져오기 완료: 알림 서비스의 가져온 구독 $N_AFTER (파티 $P_SUBS)"
[ "$N_AFTER" = "$P_SUBS" ] || log "알림: 개수가 다르다. endpoint 가 알림 서비스에서 직접 등록된 구독과 겹쳤거나 가져오는 사이 파티의 구독이 바뀌었다 — 다시 실행해 본다."
