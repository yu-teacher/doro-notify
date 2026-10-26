#!/usr/bin/env bash
# 서버(mini)에서 실행한다: 알림 서비스(CENTRAL)에서 바뀐 파티의 푸시 구독·설정을 파티 DB 로 되돌려 쓴다(LEGACY 로 롤백하기 전). 멱등. 값은 화면에 출력하지 않는다.
#
#   ssh mini 'bash -s -- --dry-run' < scripts/export-subscriptions-to-party.sh   # 읽기 전용: 개수만 비교(기본)
#   ssh mini 'bash -s -- --apply'   < scripts/export-subscriptions-to-party.sh   # 파티 DB 에 반영
#
# 하는 일: 알림 서비스의 앱 'party' 구독을 파티 push_subscriptions 에 맞춘다(같은 endpoint 는 갱신, 알림 서비스에 없는 구독은 파티에서 지운다),
#   알림 설정 3종(comment·friend·share)을 파티 push_preferences(comments·friend_requests·shares)에 덮어쓴다(알림 서비스가 기준이다).
# 파티 사용자 테이블(party_users)에 없는 사용자의 행은 건너뛴다(외래 키). 알림 서비스 DB 는 읽기만 한다.
# 쓰는 순서: 1) 이 스크립트 --apply  2) 파티를 LEGACY 로(enable-notify-shadow.sh --off --restart). 1)과 2) 사이에 바뀐 설정은 반영되지 않으니 바로 이어서 한다.
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

N_SUBS="$(scalar "$NOTIFY_DB" "SELECT count(*) FROM push_subscriptions WHERE app_id='party'")"
N_PREFS="$(scalar "$NOTIFY_DB" "SELECT count(DISTINCT user_id) FROM notify_preferences WHERE app_id='party'")"
P_SUBS="$(scalar "$PARTY_DB" "SELECT count(*) FROM push_subscriptions")"
P_PREFS="$(scalar "$PARTY_DB" "SELECT count(*) FROM push_preferences")"
log "알림 서비스: 파티 구독 $N_SUBS, 설정을 가진 사용자 $N_PREFS | 파티: 구독 $P_SUBS, 설정 행 $P_PREFS"
if [ "$APPLY" != true ]; then log "--dry-run: 여기서 종료 (--apply 로 반영)"; exit 0; fi

cleanup() { echo "DROP TABLE IF EXISTS notify_export_subs, notify_export_prefs" | psql_in "$PARTY_DB" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cat <<'SQL' | psql_in "$PARTY_DB" >/dev/null
DROP TABLE IF EXISTS notify_export_subs, notify_export_prefs;
CREATE TABLE notify_export_subs (id uuid, user_id uuid, endpoint text, p256dh text, auth text, user_agent text, created_at timestamptz, last_success_at timestamptz, fail_count int);
CREATE TABLE notify_export_prefs (user_id uuid, comments boolean, friend_requests boolean, shares boolean);
SQL

docker exec -i "$POSTGRES_CONTAINER" sh -c "psql -U \"\$POSTGRES_USER\" -d $NOTIFY_DB -v ON_ERROR_STOP=1 -c \"\\copy (SELECT id, user_id, endpoint, p256dh, auth, user_agent, created_at, last_success_at, fail_count FROM push_subscriptions WHERE app_id = 'party') TO STDOUT WITH CSV\"" </dev/null \
  | docker exec -i "$POSTGRES_CONTAINER" sh -c "psql -U \"\$POSTGRES_USER\" -d $PARTY_DB -v ON_ERROR_STOP=1 -c \"\\copy notify_export_subs FROM STDIN WITH CSV\"" >/dev/null
docker exec -i "$POSTGRES_CONTAINER" sh -c "psql -U \"\$POSTGRES_USER\" -d $NOTIFY_DB -v ON_ERROR_STOP=1 -c \"\\copy (SELECT user_id, COALESCE(bool_and(enabled) FILTER (WHERE type_id = 'comment'), true), COALESCE(bool_and(enabled) FILTER (WHERE type_id = 'friend'), true), COALESCE(bool_and(enabled) FILTER (WHERE type_id = 'share'), true) FROM notify_preferences WHERE app_id = 'party' GROUP BY user_id) TO STDOUT WITH CSV\"" </dev/null \
  | docker exec -i "$POSTGRES_CONTAINER" sh -c "psql -U \"\$POSTGRES_USER\" -d $PARTY_DB -v ON_ERROR_STOP=1 -c \"\\copy notify_export_prefs FROM STDIN WITH CSV\"" >/dev/null

cat <<'SQL' | psql_in "$PARTY_DB" >/dev/null
BEGIN;
INSERT INTO push_subscriptions (id, user_id, endpoint, p256dh, auth, user_agent, created_at, last_success_at, failure_count)
SELECT s.id, s.user_id, s.endpoint, s.p256dh, s.auth, left(s.user_agent, 300), s.created_at, s.last_success_at, s.fail_count
FROM notify_export_subs s WHERE s.user_id IN (SELECT id FROM party_users)
ON CONFLICT (endpoint) DO UPDATE
   SET user_id = EXCLUDED.user_id, p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth, user_agent = EXCLUDED.user_agent,
       last_success_at = EXCLUDED.last_success_at, failure_count = EXCLUDED.failure_count;
DELETE FROM push_subscriptions WHERE endpoint NOT IN (SELECT endpoint FROM notify_export_subs);
INSERT INTO push_preferences (user_id, comments, friend_requests, shares)
SELECT p.user_id, p.comments, p.friend_requests, p.shares FROM notify_export_prefs p WHERE p.user_id IN (SELECT id FROM party_users)
ON CONFLICT (user_id) DO UPDATE SET comments = EXCLUDED.comments, friend_requests = EXCLUDED.friend_requests, shares = EXCLUDED.shares;
COMMIT;
SQL

P_AFTER="$(scalar "$PARTY_DB" "SELECT count(*) FROM push_subscriptions")"
log "되돌려 쓰기 완료: 파티 구독 $P_AFTER (알림 서비스 $N_SUBS)"
[ "$P_AFTER" = "$N_SUBS" ] || log "알림: 개수가 다르다. 파티 사용자 테이블에 없는 사용자의 구독은 건너뛴다."
