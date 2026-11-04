#!/usr/bin/env bash
# 부하 테스트 중 서버 상태(CPU, 스레드, 커넥션 풀, 힙)를 일정 간격으로 기록한다.
# k6는 서버 안쪽을 볼 수 없어서, 응답 시간이 느려진 시점에 서버가 무엇에 막혔는지 이 기록으로 찾는다.
#
# 사용법 (저장소 루트에서, k6를 실행하기 전에 다른 터미널에서 먼저 켜 두고 끝나면 Ctrl+C):
#   load-test/collect-metrics.sh                       # 5초 간격, docs/load-test/results/ 에 저장
#   load-test/collect-metrics.sh 결과파일.tsv 2         # 파일 이름과 간격(초) 지정
# 지표 주소를 바꾸려면: METRICS_URL=http://localhost:9292/actuator/prometheus (기본값)

URL="${METRICS_URL:-http://localhost:9292/actuator/prometheus}"
OUT="${1:-docs/load-test/results/server-metrics-$(date +%Y%m%d-%H%M%S).tsv}"
INTERVAL="${2:-5}"

mkdir -p "$(dirname "$OUT")"
printf 'time\tprocess_cpu\tsystem_cpu\ttomcat_busy_threads\thikari_active\thikari_pending\theap_used_mb\n' > "$OUT"
echo "수집 시작: $OUT (${INTERVAL}초 간격, Ctrl+C로 종료)"

# 이름이 정확히 같은 지표(라벨이 붙은 것 포함)의 값을 모두 더해서 출력한다. 없으면 -
pick() {
  awk -v n="$1" '{ k = $1; if (k == n || index(k, n "{") == 1) { s += $NF; f = 1 } } END { if (f) printf "%.4f", s; else printf "-" }'
}

# 힙 메모리만 골라 MB로 출력한다
pick_heap_mb() {
  awk '{ if (index($1, "jvm_memory_used_bytes{") == 1 && index($1, "area=\"heap\"") > 0) { s += $NF; f = 1 } } END { if (f) printf "%.1f", s / 1048576; else printf "-" }'
}

while true; do
  body=$(curl -s --max-time 3 "$URL") || body=""
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$(date +%H:%M:%S)" \
    "$(echo "$body" | pick process_cpu_usage)" \
    "$(echo "$body" | pick system_cpu_usage)" \
    "$(echo "$body" | pick tomcat_threads_busy_threads)" \
    "$(echo "$body" | pick hikaricp_connections_active)" \
    "$(echo "$body" | pick hikaricp_connections_pending)" \
    "$(echo "$body" | pick_heap_mb)" >> "$OUT"
  sleep "$INTERVAL"
done
