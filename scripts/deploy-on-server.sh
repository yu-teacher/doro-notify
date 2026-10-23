#!/usr/bin/env bash
# 서버(mini)의 저장소 체크아웃에서 실행한다: 이미지를 빌드하고 컨테이너를 다시 만들고 확인한다. 실패하면 이전 이미지로 되돌린다.
# 이미지 안에서 빌드하므로(멀티스테이지) 서버에 Java 가 없어도 된다. 웹 컨테이너는 없다.
#
#   scripts/deploy-on-server.sh --dry-run                    # 계획과 설정 검증만(기본)
#   scripts/deploy-on-server.sh --apply --no-gateway         # 첫 배포: 컨테이너만 올리고 직접 확인(게이트웨이 반영 전)
#   scripts/deploy-on-server.sh --apply                      # 이후 배포: 게이트웨이 reload 와 게이트웨이 경유 확인까지
#
# 순서와 이유
#   1) 현재 이미지에 롤백 태그를 **이미지 이름으로** 붙인다(빌드 뒤에는 옛 이미지 ID 를 찾을 수 없다).
#   2) 빌드 → 컨테이너 재생성 → healthy 대기.
#   3) 게이트웨이 nginx -t + reload (nginx 는 upstream IP 를 시작·reload 때만 찾으므로 컨테이너가 새로 만들어지면 502 가 날 수 있다).
#   4) 게이트웨이 경유 확인(짧게 재시도: 재생성 직후 잠깐 연결이 거부될 수 있다). 실패하면 롤백 태그를 되살려 다시 만들고 reload.
# 환경변수: DORO_DIR(Doro 체크아웃, 기본 ../Doro). 리허설·점검용(기본값은 운영): NOTIFY_PROJECT, NOTIFY_COMPOSE_FILES, NOTIFY_ENV_FILE,
#   NOTIFY_HEALTH_TIMEOUT(초, 기본 180), GATEWAY_CONTAINER, CHECK_BASE, CHECK_CURL_ARGS, NOTIFY_CHECK_PATH(게이트웨이 경유 확인 경로)
set -Eeuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE/.."
PROJECT="${NOTIFY_PROJECT:-notify}"
COMPOSE_FILES="${NOTIFY_COMPOSE_FILES:-docker-compose.prod.yml}"
ENV_FILE_ARG=()
[ -n "${NOTIFY_ENV_FILE:-}" ] && ENV_FILE_ARG=(--env-file "$NOTIFY_ENV_FILE")
GATEWAY="${GATEWAY_CONTAINER:-doro-gateway}"
CHECK_BASE="${CHECK_BASE:-https://localhost}"
CHECK_CURL_ARGS="${CHECK_CURL_ARGS:--sk --resolve localhost:443:127.0.0.1}"
# 허브 경로의 설정 조회(로그인 없이 200). 게이트웨이 location 이 /notify/api/v1/ 를 이 서비스의 /api/v1/notify/ 로 넘겨야 한다.
CHECK_PATH="${NOTIFY_CHECK_PATH:-/notify/api/v1/config}"
API_CONTAINER=doro-notify-api
API_IMAGE=doro-notify-api
APPLY=false; USE_GATEWAY=true
for a in "$@"; do
  case "$a" in
    --apply) APPLY=true ;;
    --dry-run) APPLY=false ;;
    --no-gateway) USE_GATEWAY=false ;;
    *) echo "알 수 없는 옵션: $a" >&2; exit 2 ;;
  esac
done
TS="$(date +%Y%m%d-%H%M%S)"
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }

compose() {
  local files=() f
  for f in $COMPOSE_FILES; do files+=(-f "$f"); done
  docker compose -p "$PROJECT" ${ENV_FILE_ARG[@]+"${ENV_FILE_ARG[@]}"} "${files[@]}" "$@"
}
env_has() { # 파일에 KEY=값(비어 있지 않음)이 있는지만 본다. 값은 읽지 않는다.
  grep -Eq "^$2=.+" "$1" 2>/dev/null
}

docker info >/dev/null 2>&1 || die "docker 를 사용할 수 없다"
ENV_PATH="${NOTIFY_ENV_FILE:-.env}"
[ -f "$ENV_PATH" ] || die "$ENV_PATH 가 없다. 먼저 scripts/setup-notify-server.sh --apply"
env_has "$ENV_PATH" DB_USER && env_has "$ENV_PATH" DB_PASSWORD || die "$ENV_PATH 에 DB_USER/DB_PASSWORD 가 비어 있다"
# 없어도 서비스는 뜨지만 기능이 막힌다. 값은 읽어 출력하지 않고 있는지만 알린다.
env_has "$ENV_PATH" NOTIFY_INTERNAL_TOKENS || log "경고: $ENV_PATH 에 NOTIFY_INTERNAL_TOKENS 가 없다 — 다른 서비스의 알림 요청(/internal)이 모두 거절된다"
env_has "$ENV_PATH" NOTIFY_VAPID_PRIVATE_KEY && env_has "$ENV_PATH" NOTIFY_VAPID_PUBLIC_KEY && env_has "$ENV_PATH" NOTIFY_VAPID_SUBJECT \
  || log "경고: $ENV_PATH 에 VAPID 키·연락처(NOTIFY_VAPID_*)가 없다 — 푸시만 꺼진 채로 뜬다"
env_has "$ENV_PATH" DORO_IAM_INTERNAL_TOKEN || log "경고: $ENV_PATH 에 DORO_IAM_INTERNAL_TOKEN 이 없다 — 탈퇴한 사용자의 기기·설정이 정리되지 않는다(Doro/scripts/enable-deleted-sync.sh 에 notify 를 더해 발급)"
# 이미지 빌드가 Doro SDK 소스를 쓴다(settings.gradle 의 includeBuild, compose 의 additional_contexts). 기본은 저장소의 형제 디렉터리 Doro.
DORO_DIR="${DORO_DIR:-$(cd .. && pwd)/Doro}"
[ -d "$DORO_DIR/sdk" ] || die "Doro 체크아웃(sdk 디렉터리)이 없다: $DORO_DIR — DORO_DIR=<Doro 체크아웃 경로> 로 지정한다(서버에서는 CI 러너의 Doro 체크아웃을 쓸 수 있다)"
export DORO_DIR
compose config -q || die "compose 설정이 올바르지 않다"
log "계획: 프로젝트=$PROJECT, compose=$COMPOSE_FILES, 게이트웨이 reload·확인=$USE_GATEWAY"
if [ "$APPLY" != true ]; then log "--dry-run: 여기서 종료 (--apply 로 반영)"; exit 0; fi

# 1) 롤백 태그(이미지 이름으로)
ROLLBACK_API=false
if docker image inspect "$API_IMAGE:latest" >/dev/null 2>&1; then docker tag "$API_IMAGE:latest" "$API_IMAGE:rollback-$TS"; ROLLBACK_API=true; fi
log "롤백 태그: api=$ROLLBACK_API (rollback-$TS)"

wait_healthy() { # 컨테이너 이름
  local deadline=$((SECONDS + ${NOTIFY_HEALTH_TIMEOUT:-180}))
  until [ "$(docker inspect --format '{{.State.Health.Status}}' "$1" 2>/dev/null || echo none)" = healthy ]; do
    [ $SECONDS -lt $deadline ] || return 1
    sleep 3
  done
}
gateway_reload() {
  docker exec "$GATEWAY" nginx -t && docker exec "$GATEWAY" nginx -s reload
}

rollback() {
  log "ERROR: 롤백한다"
  if [ "$ROLLBACK_API" = true ]; then
    docker tag "$API_IMAGE:rollback-$TS" "$API_IMAGE:latest"
    compose up -d --no-build --no-deps --force-recreate notify-api || true
    wait_healthy "$API_CONTAINER" && log "이전 이미지로 복구, healthy" || log "ERROR: 복구 후에도 healthy 가 아니다. 수동 확인 필요"
  else
    log "되돌릴 이전 이미지가 없다(첫 배포). 새 컨테이너를 멈춘다"
    compose stop notify-api || true
  fi
  if [ "$USE_GATEWAY" = true ]; then gateway_reload || log "ERROR: 롤백 뒤 게이트웨이 reload 실패. 수동 확인 필요"; fi
}

# 2) 빌드 → 재생성 → healthy
log "빌드(이미지 안에서 gradle 빌드: 처음에는 몇 분 걸린다)"
compose build || { rollback; exit 1; }
compose up -d --no-deps notify-api || { rollback; exit 1; }
wait_healthy "$API_CONTAINER" || { log "ERROR: $API_CONTAINER 가 제한 시간 안에 healthy 가 되지 않았다"; docker logs --tail 25 "$API_CONTAINER" 2>&1 | sed 's/^/    /'; rollback; exit 1; }
log "컨테이너 healthy"

# 3~4) 게이트웨이 reload 와 경유 확인
if [ "$USE_GATEWAY" = true ]; then
  gateway_reload || { log "ERROR: 게이트웨이 reload 실패"; rollback; exit 1; }
  # shellcheck disable=SC2086
  code() { curl $CHECK_CURL_ARGS -o /dev/null -w '%{http_code}' "$CHECK_BASE$1" || echo 000; }
  ok=false
  for _ in 1 2 3 4 5; do
    res="$(code "$CHECK_PATH")"
    if [ "$res" = 200 ]; then ok=true; break; fi
    sleep 2
  done
  log "확인: $CHECK_PATH=$res (200 기대)"
  [ "$ok" = true ] || { rollback; exit 1; }
else
  # 게이트웨이 없이: 컨테이너 안에서 직접 확인한다
  docker exec "$API_CONTAINER" wget -qO- http://localhost:8089/api/v1/notify/config | grep -q '"success":true' || { log "ERROR: API 가 정상 응답을 주지 않는다"; rollback; exit 1; }
  log "직접 확인 통과(게이트웨이는 아직 반영 전이다: Doro 쪽 location 추가 뒤 --apply 로 다시 배포)"
fi
log "완료. 롤백 태그 rollback-$TS 는 며칠 뒤 정리한다: docker image rm $API_IMAGE:rollback-$TS"
