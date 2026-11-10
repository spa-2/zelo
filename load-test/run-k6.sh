#!/usr/bin/env bash
# k6를 도커로 실행한다 (k6를 따로 설치하지 않아도 된다).
#
# 사용법 (저장소 루트에서):
#   load-test/run-k6.sh load-test/auth/login-smoke.js
#   load-test/run-k6.sh load-test/auth/login-smoke.js -e WARMUP=5s -e DURATION=10s -e NOTE="짧게 확인"
# 스크립트 뒤의 -e 이름=값 은 그대로 k6에 전달된다 (환경변수 목록은 load-test/README.md 참고).
#
# 알아둘 점
# - 컨테이너 안의 localhost는 내 컴퓨터가 아니라 컨테이너 자신이라, 앱 주소를 host.docker.internal 로 바꿔 둔다.
#   다른 주소를 쓰려면 -e BASE_URL=... 을 뒤에 붙이면 된다.
# - 저장소 폴더를 컨테이너에 연결(-v)해서, 결과 파일이 내 컴퓨터의 docs/load-test/results/ 에 그대로 생긴다.
# - k6 이미지 버전을 고정하려면 K6_IMAGE=grafana/k6:버전 load-test/run-k6.sh ... 로 실행한다.
# - k6 지표를 Prometheus(Grafana)로도 보내려면 PROM_RW_URL을 준다 (terraform output prometheus_remote_write_url).
#     PROM_RW_URL=http://<수집기 IP>:9090/api/v1/write load-test/run-k6.sh load-test/integrated/integrated-load.js -e BASE_URL=...
#   - 한 번 실행을 구분하는 testid 태그가 붙는다 (TESTID=이름으로 정하고, 없으면 시작 시각). Grafana의 k6 대시보드에서 testid로 거른다.
#   - url 태그는 뺀다. 주소에 상품 코드, 주문 번호가 들어가서 지표 종류가 수만 개로 불어난다. name 태그는 그대로 쓴다.
#   - PROM_RW_URL을 주지 않으면 지금까지와 똑같이 동작한다 (기존 결과 파일과 요약은 그대로).

set -euo pipefail

if [ $# -lt 1 ]; then
  echo "사용법: load-test/run-k6.sh <스크립트 경로> [-e 이름=값 ...]" >&2
  exit 1
fi

SCRIPT="$1"
shift

# k6는 결과 폴더를 스스로 만들지 않아서 없으면 결과 파일 저장이 실패한다
mkdir -p docs/load-test/results/auth

IMAGE="${K6_IMAGE:-grafana/k6:latest}"
COMMIT="$(git rev-parse --short HEAD 2>/dev/null || echo '(알 수 없음)')"

# K6_PROMETHEUS_RW_*는 k6 프로그램의 환경변수라서 스크립트용 -e가 아니라 docker run의 -e로 넘겨야 한다
DOCKER_ENV=()
K6_EXTRA=()
if [ -n "${PROM_RW_URL:-}" ]; then
  TESTID="${TESTID:-$(date +%Y%m%d-%H%M%S)}"
  DOCKER_ENV=(-e "K6_PROMETHEUS_RW_SERVER_URL=$PROM_RW_URL"
    -e "K6_PROMETHEUS_RW_TREND_STATS=${PROM_RW_TREND_STATS:-p(95),p(99),avg}"
    -e "K6_PROMETHEUS_RW_PUSH_INTERVAL=${PROM_RW_PUSH_INTERVAL:-5s}")
  K6_EXTRA=(--out experimental-prometheus-rw --tag "testid=$TESTID"
    --system-tags=proto,subproto,status,method,name,group,check,error,error_code,tls_version,scenario,service,expected_response)
  echo "Prometheus로 지표 전송: $PROM_RW_URL (testid=$TESTID)" >&2
fi

# --user: 컨테이너가 만든 결과 파일의 주인이 내 계정이 되게 한다
# --add-host: 리눅스에서도 host.docker.internal 이 동작하게 한다 (맥, 윈도우에서는 없어도 된다)
exec docker run --rm -i \
  --user "$(id -u):$(id -g)" \
  --add-host=host.docker.internal:host-gateway \
  -v "$PWD":/work -w /work \
  ${DOCKER_ENV[@]+"${DOCKER_ENV[@]}"} \
  "$IMAGE" run \
  ${K6_EXTRA[@]+"${K6_EXTRA[@]}"} \
  -e BASE_URL=http://host.docker.internal:8080 \
  -e COMMIT="$COMMIT" \
  "$@" \
  "$SCRIPT"
