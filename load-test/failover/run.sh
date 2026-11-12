#!/usr/bin/env bash
# 장애 대응 테스트 한 번을 처음부터 끝까지 실행한다: 프로브 시작 → 기준선 → 장애 발생 → 복구 대기 → 프로브 종료 → 보고서.
#
# 사용 (저장소 루트에서):
#   BASE_URL=http://localhost:8080 PROBE_EMAIL=... PROBE_PASSWORD=... load-test/failover/run.sh local redis
#   BASE_URL=https://zelo.wyshin.click PROBE_EMAIL=... PROBE_PASSWORD=... load-test/failover/run.sh aws rds
#
#   대상  local  docker compose의 컨테이너를 멈췄다가 다시 켠다. 앱이 Redis나 DB가 없을 때 어떻게 동작하고 돌아오는지 본다
#         aws    실제 장애 전환을 일으킨다(ElastiCache test-failover, RDS 강제 failover). 실제 전환 시간을 잰다
#   시나리오  redis | rds
#   옵션  --baseline 60   장애 전에 정상 상태를 기록하는 시간(초)
#         --after 60      복구된 뒤 더 기록하는 시간(초)
#         --down 30       local에서 컨테이너를 멈춰 두는 시간(초)
#         --max-wait 600  aws에서 장애 전환이 끝나기를 기다리는 최대 시간(초)
#         --yes           aws 확인 질문을 건너뛴다
# 환경변수: AWS_REGION(ap-northeast-2), REDIS_RG(zelo-prod-redis), RDS_ID(zelo-prod-postgres),
#           LOCAL_REDIS(zelotest-redis), LOCAL_DB(zelotest-postgres)

set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${1:-}"; SCENARIO="${2:-}"
shift 2 2>/dev/null || true
BASELINE=60; AFTER=60; DOWN=30; MAX_WAIT=600; YES=no
while [ $# -gt 0 ]; do
  case "$1" in
    --baseline) BASELINE="$2"; shift 2 ;;
    --after) AFTER="$2"; shift 2 ;;
    --down) DOWN="$2"; shift 2 ;;
    --max-wait) MAX_WAIT="$2"; shift 2 ;;
    --yes) YES=yes; shift ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 1 ;;
  esac
done

case "$TARGET/$SCENARIO" in
  local/redis|local/rds|aws/redis|aws/rds) ;;
  *) sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
: "${BASE_URL:?BASE_URL이 필요하다}"; : "${PROBE_EMAIL:?PROBE_EMAIL이 필요하다}"; : "${PROBE_PASSWORD:?PROBE_PASSWORD가 필요하다}"
export AWS_REGION="${AWS_REGION:-ap-northeast-2}" AWS_DEFAULT_REGION="${AWS_REGION:-ap-northeast-2}"
REDIS_RG="${REDIS_RG:-zelo-prod-redis}"; RDS_ID="${RDS_ID:-zelo-prod-postgres}"
LOCAL_REDIS="${LOCAL_REDIS:-zelotest-redis}"; LOCAL_DB="${LOCAL_DB:-zelotest-postgres}"

# docker compose가 컨테이너 이름에 접두사를 붙이는 경우(예: 09bf108cd9ec_zelotest-redis)가 있어서 이름이 아니라 compose 서비스로 찾는다
if [ "$TARGET" = local ]; then
  SERVICE=redis; FALLBACK_NAME="$LOCAL_REDIS"
  [ "$SCENARIO" = rds ] && { SERVICE=postgres; FALLBACK_NAME="$LOCAL_DB"; }
  CID="$(docker compose ps -q "$SERVICE" 2>/dev/null | head -1)"
  [ -z "$CID" ] && CID="$(docker ps -q --filter "name=$FALLBACK_NAME" | head -1)"
  if [ -z "$CID" ]; then
    echo "실행 중인 $SERVICE 컨테이너를 찾지 못했다. 저장소 루트에서 'docker compose up -d --wait'를 먼저 실행해라." >&2
    exit 1
  fi
  CNAME="$(docker inspect -f '{{.Name}}' "$CID" | sed 's#^/##')"
  echo "장애 대상 컨테이너: $CNAME ($SERVICE)"
fi

OUT="docs/failover-test/results/$(date +%Y%m%d-%H%M%S)-$TARGET-$SCENARIO"
mkdir -p "$OUT"

now_iso() { python3 -c 'from datetime import datetime; print(datetime.now().astimezone().isoformat(timespec="milliseconds"))'; }
event() { echo "$(now_iso),$1,$2" >> "$OUT/events.csv"; echo "[$(date +%H:%M:%S)] $1 $2"; }

if [ "$TARGET" = aws ] && [ "$YES" != yes ]; then
  echo "⚠️  $BASE_URL 에 연결된 실제 환경에서 $SCENARIO 장애 전환을 일으킨다."
  echo "    ${SCENARIO}가 1~2분(RDS) 또는 수십 초(Redis) 동안 영향을 받고, 알람과 알림이 울릴 수 있다."
  read -r -p "계속하려면 yes를 입력: " answer
  [ "$answer" = yes ] || { echo "취소했다"; exit 1; }
fi

echo "결과 폴더: $OUT"
# 삭제 프로브가 쓸 배송지를 측정 시간(초)만큼 미리 만든다. aws는 장애 전환이 길 수 있어 여유를 더 둔다
if [ "$TARGET" = aws ]; then PREFILL=$((BASELINE + AFTER + 300)); else PREFILL=$((BASELINE + DOWN + AFTER + 30)); fi
python3 "$DIR/probe.py" --base-url "$BASE_URL" --out "$OUT" --prefill "$PREFILL" > "$OUT/probe.log" 2>&1 &
PROBE_PID=$!
# 로그인에 성공해서 프로브가 시작될 때까지 기다린다
for _ in $(seq 1 300); do  # 배송지를 미리 만드는 시간 포함
  grep -q "프로브를 시작" "$OUT/probe.log" 2>/dev/null && break
  kill -0 "$PROBE_PID" 2>/dev/null || { echo "프로브가 시작하지 못했다:"; cat "$OUT/probe.log"; exit 1; }
  sleep 1
done
trap 'kill -INT "$PROBE_PID" 2>/dev/null' INT TERM

event baseline_start "정상 상태 기록 ${BASELINE}초"
sleep "$BASELINE"

case "$TARGET/$SCENARIO" in
  local/redis|local/rds)
    event inject "docker stop $CNAME"
    if ! docker stop "$CID" > /dev/null; then
      echo "컨테이너를 멈추지 못했다. 테스트를 중단한다." >&2
      kill -INT "$PROBE_PID" 2>/dev/null; wait "$PROBE_PID" 2>/dev/null; exit 1
    fi
    if [ "$(docker inspect -f '{{.State.Running}}' "$CID")" != false ]; then
      echo "컨테이너가 아직 실행 중이다. 테스트를 중단한다." >&2
      kill -INT "$PROBE_PID" 2>/dev/null; wait "$PROBE_PID" 2>/dev/null; exit 1
    fi
    sleep "$DOWN"
    event restore "docker start $CNAME"
    docker start "$CID" > /dev/null || echo "컨테이너를 다시 켜지 못했다. 직접 'docker start $CNAME'를 실행해라." >&2
    ;;
  aws/redis)
    event inject "elasticache test-failover $REDIS_RG"
    aws elasticache describe-replication-groups --replication-group-id "$REDIS_RG" \
      --query 'ReplicationGroups[0].NodeGroups[0].NodeGroupMembers[].[CacheClusterId,PreferredAvailabilityZone,CurrentRole]' --output text > "$OUT/aws-before.txt" 2>&1
    aws elasticache test-failover --replication-group-id "$REDIS_RG" --node-group-id 0001 > "$OUT/aws-command.json" 2>&1 \
      || { echo "test-failover 실패:"; cat "$OUT/aws-command.json"; }
    START=$(date +%s)
    while [ $(( $(date +%s) - START )) -lt "$MAX_WAIT" ]; do
      sleep 5
      STATUS=$(aws elasticache describe-replication-groups --replication-group-id "$REDIS_RG" --query 'ReplicationGroups[0].Status' --output text 2>/dev/null)
      [ "$STATUS" = available ] && [ $(( $(date +%s) - START )) -ge 20 ] && break
    done
    event group_available "ElastiCache 상태 available"
    aws elasticache describe-replication-groups --replication-group-id "$REDIS_RG" \
      --query 'ReplicationGroups[0].NodeGroups[0].NodeGroupMembers[].[CacheClusterId,PreferredAvailabilityZone,CurrentRole]' --output text > "$OUT/aws-after.txt" 2>&1
    ;;
  aws/rds)
    event inject "rds reboot-db-instance --force-failover $RDS_ID"
    aws rds describe-db-instances --db-instance-identifier "$RDS_ID" \
      --query 'DBInstances[0].[AvailabilityZone,SecondaryAvailabilityZone,DBInstanceStatus]' --output text > "$OUT/aws-before.txt" 2>&1
    aws rds reboot-db-instance --db-instance-identifier "$RDS_ID" --force-failover > "$OUT/aws-command.json" 2>&1 \
      || { echo "reboot-db-instance 실패:"; cat "$OUT/aws-command.json"; }
    START=$(date +%s)
    while [ $(( $(date +%s) - START )) -lt "$MAX_WAIT" ]; do
      sleep 5
      STATUS=$(aws rds describe-db-instances --db-instance-identifier "$RDS_ID" --query 'DBInstances[0].DBInstanceStatus' --output text 2>/dev/null)
      [ "$STATUS" = available ] && [ $(( $(date +%s) - START )) -ge 30 ] && break
    done
    event db_available "RDS 상태 available"
    aws rds describe-db-instances --db-instance-identifier "$RDS_ID" \
      --query 'DBInstances[0].[AvailabilityZone,SecondaryAvailabilityZone,DBInstanceStatus]' --output text > "$OUT/aws-after.txt" 2>&1
    ;;
esac

event after_start "복구 후 ${AFTER}초 더 기록"
sleep "$AFTER"

kill -INT "$PROBE_PID" 2>/dev/null
wait "$PROBE_PID" 2>/dev/null

# AWS가 남긴 이벤트(failover 시작과 끝의 공식 시각)를 증거로 같이 저장한다
if [ "$TARGET" = aws ]; then
  if [ "$SCENARIO" = rds ]; then
    aws rds describe-events --source-type db-instance --source-identifier "$RDS_ID" --duration 30 --output json > "$OUT/aws-events.json" 2>&1
  else
    aws elasticache describe-events --source-type replication-group --source-identifier "$REDIS_RG" --duration 30 --output json > "$OUT/aws-events.json" 2>&1
  fi
fi

python3 "$DIR/report.py" "$OUT"
