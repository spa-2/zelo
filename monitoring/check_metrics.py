"""Warm-up 후 실행하는 Prometheus/Hikari 노출 검사. 임계치 성능 판정은 하지 않는다."""
import sys
import urllib.request

REQUIRED = (
    'zelo_chat_inflight', 'zelo_chat_request_seconds_bucket',
    'zelo_chat_reply_seconds_bucket', 'zelo_llm_call_seconds_bucket',
    'zelo_llm_attempt_seconds_bucket', 'zelo_llm_execution_started_total',
    'zelo_llm_inflight', 'http_server_requests_seconds_bucket',
    'hikaricp_connections_active', 'hikaricp_connections_pending',
    'hikaricp_connections_max', 'hikaricp_connections_acquire_seconds_count',
    'resilience4j_circuitbreaker_state',
)


def main():
    if len(sys.argv) != 2:
        raise SystemExit('사용법: python3 monitoring/check_metrics.py http://host:9292/actuator/prometheus')
    with urllib.request.urlopen(sys.argv[1], timeout=10) as response:
        body = response.read().decode()
    names = {line.split('{', 1)[0].split()[0] for line in body.splitlines()
             if line.strip() and not line.startswith('#')}
    missing = [name for name in REQUIRED if name not in names]
    for name in REQUIRED:
        print(('MISSING ' if name in missing else 'OK ') + name)
    if missing:
        raise SystemExit('필수 지표 누락: 정상 채팅/조회 warm-up 및 수집 설정을 확인하세요.')


if __name__ == '__main__':
    main()
