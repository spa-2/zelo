#!/bin/sh
# k6를 돌리는 동안 다른 탭에서 함께 실행해 서버 지표를 5초마다 CSV 한 줄로 남긴다. Ctrl-C로 멈춘다
#   load-test/chat/record-metrics.sh docs/load-test/results/chat/chat-load-server.csv
# 열: 시각, Tomcat 바쁜 스레드, DB 커넥션 사용 중, DB 커넥션 대기, 처리 중인 채팅 요청, 서킷 상태,
#     서킷이 막은 호출 누적, 시간 초과 FALLBACK 누적(AI_002), 서킷 차단 FALLBACK 누적(AI_008), 성공한 채팅 답변 누적
OUT=${1:?usage: record-metrics.sh <out.csv>}
URL=${METRICS_URL:-http://localhost:9292/actuator/prometheus}
INTERVAL=${INTERVAL:-5}

mkdir -p "$(dirname "$OUT")"
echo "time,tomcat_busy,hikari_active,hikari_pending,chat_inflight,circuit,not_permitted,fallback_timeout,fallback_circuit,reply_success" > "$OUT"

while true; do
    curl -s "$URL" | awk -v time="$(date +%H:%M:%S)" '
        /^tomcat_threads_busy_threads\{.*http-nio-8080/ { busy = $2 }
        /^hikaricp_connections_active\{/ { active = $2 }
        /^hikaricp_connections_pending\{/ { pending = $2 }
        /^zelo_chat_inflight\{/ { inflight = $2 }
        /^resilience4j_circuitbreaker_state\{.*name="llm"/ && $2 == 1.0 { match($0, /state="[a-z_]+"/); circuit = substr($0, RSTART + 7, RLENGTH - 8) }
        /^resilience4j_circuitbreaker_not_permitted_calls_total\{.*name="llm"/ { notPermitted = $2 }
        /^zelo_chat_fallback_total\{.*code="AI_002"/ { timeout = $2 }
        /^zelo_chat_fallback_total\{.*code="AI_008"/ { circuitOpen = $2 }
        /^zelo_chat_reply_seconds_count\{.*outcome="SUCCESS"/ { success += $2 }
        END { printf "%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n", time, busy, active, pending, inflight, circuit, notPermitted + 0, timeout + 0, circuitOpen + 0, success + 0 }
    ' | tee -a "$OUT"
    sleep "$INTERVAL"
done
