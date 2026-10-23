#!/usr/bin/env bash
# 서버(mini)에서 실행한다: 알림 서비스를 부르는 서비스(파티·블로그 …)의 내부 API 호출자 토큰을 만들어 ~/doro-notify/.env 에 넣는다.
# 여러 번 실행해도 결과가 같다(이미 있는 항목은 건드리지 않는다). 토큰 값은 화면에 출력하지 않는다.
#
#   ssh mini 'bash -s -- --dry-run' < scripts/issue-caller-tokens.sh                   # 읽기 전용: 어떤 항목이 있고 없는지(기본)
#   ssh mini 'bash -s -- --apply' < scripts/issue-caller-tokens.sh                     # 없는 항목만 만들어 .env 에 넣는다(백업 후, 컨테이너는 그대로)
#   ssh mini 'bash -s -- --apply --restart' < scripts/issue-caller-tokens.sh           # 위 + 알림 컨테이너를 다시 만들어 새 값을 읽게 한다(몇 초)
#   옵션 --apps party,blog   토큰을 만들 호출자 이름(= 알림 앱 id, 소문자·숫자·하이픈). 기본 party,blog
#
# 하는 일: NOTIFY_INTERNAL_TOKENS('이름:토큰,이름:토큰') 에 없는 이름마다 openssl rand -hex 32 로 만든 토큰을 더한다(32자 이상 요구).
# 하지 않는 일: 호출하는 서비스(파티·블로그)의 .env 에 같은 토큰을 주는 것. 그 서비스가 알림 서비스를 부르는 코드가 배포될 때 함께 한다.
# 환경변수(리허설·점검용, 기본값은 운영): NOTIFY_DIR
set -Eeuo pipefail

DIR="${NOTIFY_DIR:-$HOME/doro-notify}"
ENV_FILE="$DIR/.env"
APPS="party,blog"
APPLY=false; RESTART=false
while [ $# -gt 0 ]; do
  case "$1" in
    --apply) APPLY=true ;;
    --dry-run) APPLY=false ;;
    --restart) RESTART=true ;;
    --apps) shift; APPS="${1:-}" ;;
    *) echo "사용법: bash -s -- [--dry-run|--apply] [--restart] [--apps party,blog]" >&2; exit 2 ;;
  esac
  shift
done

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }
get_var() { grep -m1 "^$2=" "$1" 2>/dev/null | cut -d= -f2- || true; }
set_var() {
  local file="$1" key="$2" value="$3" tmp
  tmp="$(mktemp "$file.XXXXXX")"
  grep -v "^$key=" "$file" > "$tmp" || true
  printf '%s=%s\n' "$key" "$value" >> "$tmp"
  chmod 600 "$tmp"; mv "$tmp" "$file"
}
has_entry() { get_var "$ENV_FILE" NOTIFY_INTERNAL_TOKENS | tr ',' '\n' | grep -q "^$1:."; }

[ -f "$ENV_FILE" ] || die "$ENV_FILE 가 없다(먼저 setup-notify-server.sh)"
IFS=',' read -r -a NAMES <<< "$APPS"
[ "${#NAMES[@]}" -gt 0 ] || die "--apps 가 비어 있다"
for n in "${NAMES[@]}"; do
  printf '%s' "$n" | grep -Eq '^[a-z0-9-]+$' || die "호출자 이름은 소문자·숫자·하이픈만 쓴다: '$n'"
done

MISSING=()
for n in "${NAMES[@]}"; do
  if has_entry "$n"; then log "[$n] 호출자 토큰: 있음(그대로 둔다)"; else log "[$n] 호출자 토큰: 없음"; MISSING+=("$n"); fi
done
if [ "${#MISSING[@]}" -eq 0 ]; then log "만들 항목이 없다."; exit 0; fi
if [ "$APPLY" != true ]; then log "--dry-run: 여기서 종료 (--apply 로 반영)"; exit 0; fi

TS="$(date +%Y%m%d-%H%M%S)"
cp -p "$ENV_FILE" "$ENV_FILE.bak-tokens-$TS"; log ".env 백업: $ENV_FILE.bak-tokens-$TS"
CUR="$(get_var "$ENV_FILE" NOTIFY_INTERNAL_TOKENS)"
for n in "${MISSING[@]}"; do
  TOKEN="$(openssl rand -hex 32)"
  [ "${#TOKEN}" -ge 32 ] || die "토큰 생성에 실패했다"
  CUR="${CUR:+$CUR,}$n:$TOKEN"
  unset TOKEN
  log "[$n] 호출자 토큰을 만들었다(값은 출력하지 않는다)"
done
set_var "$ENV_FILE" NOTIFY_INTERNAL_TOKENS "$CUR"
unset CUR

if [ "$RESTART" = true ]; then
  log "알림 컨테이너를 다시 만든다(새 토큰을 읽게 한다)"
  ( cd "$DIR" && docker compose -p notify -f docker-compose.prod.yml up -d --no-build --no-deps --force-recreate notify-api ) >/dev/null
  for _ in $(seq 1 40); do
    [ "$(docker inspect doro-notify-api --format '{{.State.Health.Status}}' 2>/dev/null)" = healthy ] && { log "doro-notify-api 가 healthy 로 돌아왔다"; exit 0; }
    sleep 3
  done
  die "doro-notify-api 가 healthy 로 돌아오지 않았다(.env 백업: $ENV_FILE.bak-tokens-$TS)"
fi
log "컨테이너는 그대로다. 새 토큰은 --restart 또는 다음 배포 때 반영된다."
