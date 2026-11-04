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

# --user: 컨테이너가 만든 결과 파일의 주인이 내 계정이 되게 한다
# --add-host: 리눅스에서도 host.docker.internal 이 동작하게 한다 (맥, 윈도우에서는 없어도 된다)
exec docker run --rm -i \
  --user "$(id -u):$(id -g)" \
  --add-host=host.docker.internal:host-gateway \
  -v "$PWD":/work -w /work \
  "$IMAGE" run \
  -e BASE_URL=http://host.docker.internal:8080 \
  -e COMMIT="$COMMIT" \
  "$@" \
  "$SCRIPT"
