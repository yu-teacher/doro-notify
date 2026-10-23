#!/usr/bin/env bash
# 서버(mini)에서 한 번 실행한다: 도로 알림 배포 전 서버 준비. 여러 번 실행해도 결과가 같다(멱등). 비밀 값은 화면에 출력하지 않는다.
#
#   scripts/setup-notify-server.sh --dry-run                    # 바꿀 것만 출력(기본)
#   scripts/setup-notify-server.sh --apply                      # 실제 반영(VAPID 키는 건드리지 않는다)
#   scripts/setup-notify-server.sh --apply --vapid-from-party   # 파티가 쓰는 VAPID 키를 이 서비스 .env 로 복사(이미 등록된 기기가 재구독 없이 이어진다)
#   scripts/setup-notify-server.sh --apply --vapid-generate     # 새 VAPID 키 쌍을 만든다(기기가 아직 없는 새 서비스일 때만: 키가 바뀌면 모든 구독이 무효다)
#   옵션 --vapid-subject <mailto:…|https://…>                    # VAPID 연락처(없으면 이 값만 비워 두고 알려 준다)
#
# 하는 일
#   1) Postgres 에 service_notify DB 를 만든다(없을 때만).
#   2) ~/doro-notify/.env 를 만든다(없을 때만): DB 접속 계정·비밀번호를 ~/doro/.env 의 POSTGRES_USER/POSTGRES_PASSWORD 에서 복사한다.
#      내부 API 호출자 토큰(NOTIFY_INTERNAL_TOKENS)·탈퇴 동기화 토큰(DORO_IAM_INTERNAL_TOKEN)은 값을 만들지 않는다(아래 "남은 일").
#      이 스크립트는 그 값들이 **있는지만** 알려 주고 값은 읽어 출력하지 않는다.
#   3) (옵션) VAPID 키를 .env 에 넣는다. 키가 이미 있으면 덮어쓰지 않는다. 값은 출력하지 않는다.
# 하지 않는 일: 서비스 전용 DB 계정(notify_app) 만들기(Doro/scripts/split-db-accounts.sh 의 몫), 게이트웨이 반영, 컨테이너 기동, 호출자 서비스(파티·블로그)에 토큰 주기.
# 환경변수(리허설·점검용, 기본값은 운영): POSTGRES_CONTAINER, DORO_ENV, NOTIFY_DIR, PARTY_ENV
set -Eeuo pipefail

# ssh mini 'bash -s' < 스크립트 처럼 표준입력으로 실행하면 BASH_SOURCE 가 비어 있다(set -u 에서 오류). 이때는 현재 디렉터리를 쓴다(lib/vapid.sh 는 --vapid-generate 에서만 필요).
HERE="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" 2>/dev/null && pwd || pwd)"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-doro-postgres}"
DORO_ENV="${DORO_ENV:-$HOME/doro/.env}"
PARTY_ENV="${PARTY_ENV:-$HOME/doro-party/.env}"
DIR="${NOTIFY_DIR:-$HOME/doro-notify}"
DB="service_notify"
ENV_FILE="$DIR/.env"
APPLY=false; VAPID_MODE=none; SUBJECT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --apply) APPLY=true ;;
    --dry-run) APPLY=false ;;
    --vapid-from-party) VAPID_MODE=party ;;
    --vapid-generate) VAPID_MODE=generate ;;
    --vapid-subject) shift; SUBJECT="${1:-}" ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 2 ;;
  esac
  shift
done
TS="$(date +%Y%m%d-%H%M%S)"
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }

# 키 이름만 확인하고 값은 읽어도 출력하지 않는다
env_get() { grep -E "^$2=" "$1" 2>/dev/null | head -1 | cut -d= -f2- || true; }
env_has() { grep -Eq "^$2=.+" "$1" 2>/dev/null; }
psql_in() { docker exec -i "$POSTGRES_CONTAINER" sh -c "psql -U \"\$POSTGRES_USER\" -d $1 -v ON_ERROR_STOP=1 -tA"; }
state() { if [ -f "$ENV_FILE" ] && env_has "$ENV_FILE" "$1"; then echo "있음"; else echo "없음"; fi; }

if [ -n "$SUBJECT" ]; then
  case "$SUBJECT" in mailto:*|https://*) ;; *) die "--vapid-subject 는 mailto: 또는 https:// 로 시작해야 한다" ;; esac
fi
docker info >/dev/null 2>&1 || die "docker 를 사용할 수 없다"
docker inspect "$POSTGRES_CONTAINER" >/dev/null 2>&1 || die "$POSTGRES_CONTAINER 컨테이너가 없다"
[ -f "$DORO_ENV" ] || die "$DORO_ENV 가 없다"
env_has "$DORO_ENV" POSTGRES_USER && env_has "$DORO_ENV" POSTGRES_PASSWORD || die "$DORO_ENV 에 POSTGRES_USER/POSTGRES_PASSWORD 가 없다"
if [ "$VAPID_MODE" = party ]; then
  env_has "$PARTY_ENV" PARTY_VAPID_PUBLIC_KEY && env_has "$PARTY_ENV" PARTY_VAPID_PRIVATE_KEY || die "$PARTY_ENV 에 PARTY_VAPID_PUBLIC_KEY/PARTY_VAPID_PRIVATE_KEY 가 없다"
fi

need_db=false; need_env=false
[ "$(echo "SELECT 1 FROM pg_database WHERE datname='$DB'" | psql_in postgres)" = 1 ] || need_db=true
[ -f "$ENV_FILE" ] || need_env=true
log "계획: DB $DB 생성=$need_db, $ENV_FILE 생성=$need_env, VAPID=$VAPID_MODE"
log "NOTIFY_INTERNAL_TOKENS($ENV_FILE): $(state NOTIFY_INTERNAL_TOKENS) — 없으면 모든 내부 요청이 거절된다"
log "DORO_IAM_INTERNAL_TOKEN($ENV_FILE): $(state DORO_IAM_INTERNAL_TOKEN) — 없으면 탈퇴 동기화 호출이 거절된다(경고만, 서비스는 뜬다)"
log "NOTIFY_VAPID_PUBLIC_KEY/PRIVATE_KEY($ENV_FILE): $(state NOTIFY_VAPID_PUBLIC_KEY)/$(state NOTIFY_VAPID_PRIVATE_KEY) — 없으면 푸시만 꺼진다"
if [ "$APPLY" != true ]; then log "--dry-run: 여기서 종료 (--apply 로 반영)"; exit 0; fi

if [ "$need_db" = true ]; then
  echo "CREATE DATABASE $DB" | psql_in postgres >/dev/null
  log "DB $DB 생성"
fi

mkdir -p "$DIR"
if [ "$need_env" = true ]; then
  umask 077
  {
    echo "# 도로 알림 서버 설정 (setup-notify-server.sh 가 $TS 에 생성). 배포가 덮어쓰지 않는다."
    echo "# 서비스 전용 계정으로 바꾸려면 Doro/scripts/split-db-accounts.sh --apply notify."
    echo "DB_USER=$(env_get "$DORO_ENV" POSTGRES_USER)"
    echo "DB_PASSWORD=$(env_get "$DORO_ENV" POSTGRES_PASSWORD)"
  } > "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  log "$ENV_FILE 생성(값은 출력하지 않는다)"
fi

if [ "$VAPID_MODE" != none ]; then
  if env_has "$ENV_FILE" NOTIFY_VAPID_PUBLIC_KEY || env_has "$ENV_FILE" NOTIFY_VAPID_PRIVATE_KEY; then
    log "VAPID 키가 이미 있어 건드리지 않는다(바꾸면 모든 구독이 무효가 된다)"
  else
    umask 077
    if [ "$VAPID_MODE" = party ]; then
      PUB="$(env_get "$PARTY_ENV" PARTY_VAPID_PUBLIC_KEY)"; PRIV="$(env_get "$PARTY_ENV" PARTY_VAPID_PRIVATE_KEY)"
      [ -n "$SUBJECT" ] || SUBJECT="$(env_get "$PARTY_ENV" PARTY_VAPID_SUBJECT)"
    else
      # shellcheck source=lib/vapid.sh
      . "$HERE/lib/vapid.sh"
      vapid_generate || die "VAPID 키를 만들지 못했다"
      PUB="$VAPID_PUBLIC"; PRIV="$VAPID_PRIVATE"
    fi
    {
      echo "NOTIFY_VAPID_PUBLIC_KEY=$PUB"
      echo "NOTIFY_VAPID_PRIVATE_KEY=$PRIV"
      [ -z "$SUBJECT" ] || echo "NOTIFY_VAPID_SUBJECT=$SUBJECT"
    } >> "$ENV_FILE"
    unset PUB PRIV VAPID_PUBLIC VAPID_PRIVATE
    log "VAPID 키를 $ENV_FILE 에 넣었다(값은 출력하지 않는다)"
  fi
fi
if [ -n "$SUBJECT" ] && ! env_has "$ENV_FILE" NOTIFY_VAPID_SUBJECT; then
  echo "NOTIFY_VAPID_SUBJECT=$SUBJECT" >> "$ENV_FILE"
  log "NOTIFY_VAPID_SUBJECT 추가"
fi
env_has "$ENV_FILE" NOTIFY_VAPID_SUBJECT || log "알림: NOTIFY_VAPID_SUBJECT(mailto:… 또는 https://…)가 없어 푸시가 꺼진 채로 뜬다. .env 에 직접 넣거나 --vapid-subject 로 다시 실행한다"

cat <<MSG

서버 준비 완료. 남은 일:
  1) 저장소를 $DIR 로 가져온다(git clone 또는 rsync)
  2) 내부 API 호출자 토큰: '이름:토큰' 목록(이름 = 앱 id: party, blog, games, menu, hub)을 $ENV_FILE 의
     NOTIFY_INTERNAL_TOKENS 에 넣고, 같은 토큰을 각 호출 서비스의 .env 에 준다(값은 openssl rand -hex 32 처럼 길고 무작위여야 한다).
  3) 탈퇴 동기화: Doro 레포 scripts/enable-deleted-sync.sh 의 서비스 목록에 notify 를 더해 DORO_IAM_INTERNAL_TOKEN 을 발급한다(Doro 쪽 변경)
  4) scripts/deploy-on-server.sh --apply --no-gateway   # 컨테이너 기동·헬스 확인(게이트웨이 반영 전)
  5) 게이트웨이 location(앱 접두사 아래 + 허브 /notify/api/v1/)과 X-Doro-Notify-App 헤더는 Doro 쪽 변경이다(docs/DESIGN.md '신뢰 전제')
  6) (권장) Doro 레포의 split-db-accounts.sh 에 notify 항목을 더한 뒤 --apply notify 로 서비스 전용 DB 계정(notify_app)으로 전환
MSG
