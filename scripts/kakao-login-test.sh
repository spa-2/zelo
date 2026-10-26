#!/usr/bin/env bash
# 카카오 소셜 로그인 수동 테스트용.
# 카카오 로그인 후 이동한 주소창의 URL 전체를 붙여 넣으면 code를 꺼내 서버로 보낸다.
# 사용법: KAKAO_CLIENT_ID=<REST API 키> ./scripts/kakao-login-test.sh

KAKAO_CLIENT_ID="72453c333ff65216597b0cd9d592cfaa"

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
REDIRECT_URI="${REDIRECT_URI:-http://localhost:8080/auth/kakao/callback}"

if [ -z "${KAKAO_CLIENT_ID:-}" ]; then
  echo "KAKAO_CLIENT_ID 환경변수(REST API 키)가 필요합니다." >&2
  exit 1
fi

AUTH_URL=$(python3 -I - "$KAKAO_CLIENT_ID" "$REDIRECT_URI" <<'PY'
import sys, urllib.parse as u
query = u.urlencode({
    "client_id": sys.argv[1],
    "redirect_uri": sys.argv[2],
    "response_type": "code",
    "scope": "profile_nickname,account_email",
}, quote_via=u.quote, safe=",")
print("https://kauth.kakao.com/oauth/authorize?" + query)
PY
)

echo "1) 브라우저가 열리면 카카오 로그인을 하세요."
echo "   (로그인 후 에러 JSON이나 404 화면이 떠도 정상입니다)"
if ! open "$AUTH_URL" 2>/dev/null; then
  echo "   자동으로 안 열리면 아래 주소를 직접 여세요:"
  echo "   $AUTH_URL"
fi
echo
echo "2) 이동한 페이지의 주소창 URL 전체를 복사해서 아래에 붙여 넣고 엔터를 누르세요."
printf "URL> "
read -r CALLBACK_URL

# code 추출과 JSON 생성을 python에 맡겨서 따옴표나 디코딩 실수를 막는다.
PAYLOAD=$(python3 -I - "$CALLBACK_URL" "$REDIRECT_URI" <<'PY'
import sys, json, urllib.parse as u
query = u.parse_qs(u.urlparse(sys.argv[1].strip()).query)
if "error" in query:
    desc = query.get("error_description", [""])[0]
    sys.exit("카카오가 오류를 돌려줬습니다: " + query["error"][0] + " " + desc)
if "code" not in query:
    sys.exit("URL에 code가 없습니다. 주소창 URL 전체를 붙여 넣었는지 확인하세요.")
print(json.dumps({"code": query["code"][0], "redirectUri": sys.argv[2]}))
PY
)

echo
echo "3) 서버로 전송합니다 (code는 1회용이라 실패하면 처음부터 다시 하세요)."
curl -sS -i -X POST "$BASE_URL/api/auth/social/kakao/login" \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD"
echo