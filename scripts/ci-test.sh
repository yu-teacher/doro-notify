#!/usr/bin/env bash
# CI 와 같은 검증을 로컬에서도 그대로 돌린다: 격리된 Postgres 를 올리고 테스트를 실행한 뒤 내린다. 개발·운영 DB 는 건드리지 않는다.
#
#   scripts/ci-test.sh            # 백엔드(기본)
#   scripts/ci-test.sh backend    # 백엔드: 스크립트 문법 점검 + 격리 Postgres 위의 gradle test
#   CI_PROJECT=notify-ci-b CI_DB_PORT=25444 scripts/ci-test.sh backend   # 다른 실행과 겹치지 않게
#
# 필요: Docker, Java 25, 형제 디렉터리의 Doro 체크아웃(../Doro/sdk). 웹 컨테이너가 없는 서비스라 프런트·Playwright 단계는 없다.
# 포트는 CI_DB_PORT 로 바꿀 수 있다(기본 25434: games·party·blog 25432, interview 25433 과 겹치지 않는다).
set -Eeuo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
TARGET="${1:-backend}"
export CI_DB_PASSWORD="${CI_DB_PASSWORD:-ci-only-password}"
export CI_DB_PORT="${CI_DB_PORT:-25434}"
STACK_TIMEOUT_SEC="${CI_STACK_TIMEOUT_SEC:-120}"
# 같은 체크아웃에서 CI 스크립트를 동시에 돌릴 때는 CI_PROJECT 와 CI_DB_PORT 를 서로 다르게 준다(안 그러면 한쪽이 상대의 DB 를 내린다).
CI_PROJECT="${CI_PROJECT:-notify-ci}"
COMPOSE=(docker compose -p "$CI_PROJECT" -f docker-compose.ci.yml)

# 백엔드 빌드는 형제 디렉터리 ../Doro 의 SDK 를 쓴다(settings.gradle 의 includeBuild). 없으면 친절하게 알린다.
need_doro() { [ -d "${DORO_DIR:-../Doro}/sdk" ] || { echo "Doro 체크아웃(../Doro/sdk)이 없다. 형제 디렉터리에 Doro 레포를 두거나 DORO_DIR 을 지정한다" >&2; exit 2; }; }

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*"; exit 1; }

run_backend() {
  need_doro
  log "== 스크립트 문법 점검 =="
  for f in scripts/*.sh; do bash -n "$f" || die "$f 문법 오류"; done
  log "== 백엔드: 격리 Postgres(포트 $CI_DB_PORT) =="
  "${COMPOSE[@]}" up -d --wait --wait-timeout "$STACK_TIMEOUT_SEC" || die "Postgres 가 준비되지 않았다"
  trap '"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true' EXIT
  DB_PASSWORD="$CI_DB_PASSWORD" DB_PORT="$CI_DB_PORT" ./gradlew test --no-daemon
}

case "$TARGET" in
  backend|all) run_backend ;;
  *) die "알 수 없는 대상: $TARGET (backend)" ;;
esac
log "OK"
