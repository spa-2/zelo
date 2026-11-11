# 채팅·LLM 성능 관측

## 측정 범위

| 지표(Micrometer 이름) | 분모·범위 |
|---|---|
| zelo.chat.request | START/SEND/RETRY 동기 HTTP 처리 전체. 인증·제한·멱등 재응답 포함, 상태 코드별 집계. 네트워크/ALB 대기는 k6로 측정 |
| zelo.chat.inflight | 위 세 API에서 아직 필터를 빠져나오지 않은 요청 |
| zelo.chat.reply | 사용자 메시지 저장·이력 조회 이후 답변 생성. 멱등 재응답은 제외 |
| zelo.llm.call | 재시도 포함 게이트웨이 호출 |
| zelo.llm.attempt | 서킷 거절도 포함한 시도. 외부 HTTP 횟수가 아님 |
| zelo.llm.execution.started | 실행기 안에서 실제 시작한 delegate 작업. 서킷 거절·시간 예산 생략 제외 |
| zelo.llm.inflight | delegate 작업이 실제 종료할 때까지 유지. 타임아웃 응답 후 남은 작업도 포함 |

`chat.reply`의 outcome은 기존 SUCCESS/오류 코드다. result는 NORMAL, DEGRADED(안내 대체), PARTIAL, PARTIAL_DEGRADED, BUSY, NOT_APPLIED, NO_QUOTE, FAILED다. NORMAL도 자연어 사실성이 검증됐다는 의미는 아니다. 오류가 발생하면 result=FAILED이며 앞서 발생한 부분 적용·안내 대체는 기존 개별 카운터로 확인한다.

게이트웨이 작업 한 번에 도구 왕복 HTTP가 여러 번 발생할 수 있다. 실제 외부 요청 수는 **HTTP 대역 서버의 수신 카운터**와 대조해야 한다. 이번 변경은 대역 서버나 부하 시나리오를 추가하지 않는다. 토큰 지표는 성공적으로 검증된 응답의 관측 사용량이며 실패·타임아웃 비용 전체를 의미하지 않는다.

진행 수는 프로세스 로컬 값이다. 서버가 종료되면 해당 시계열도 중단된다. 짧은 작업은 scrape 사이에 끝나 gauge에 보이지 않을 수 있으므로 시작 카운터와 완료 타이머도 함께 본다. 계수는 서비스 호출 흐름을 바꾸지 않는다.

## 실행과 수집

1. 앱의 기존 프로필에 `performance`를 추가한다. 원문·SQL DEBUG/TRACE를 억제하며 DB 주소·시크릿은 변경하지 않는다. SSM 등 우선순위가 높은 설정에 의해 덮이는지 실제 로그로 확인한다.
**AWS에서는 Terraform이 수집기(Prometheus + Grafana)를 만든다(`terraform/monitoring.tf`).** 앱 서버를 `Name` 태그로 자동 탐색(`ec2_sd`)하므로 아래 2~4번의 `targets.json` 작업은 필요 없다. 서버가 교체되거나 늘어나도 약 30초 안에 대상이 갱신된다. Grafana는 `terraform output grafana_url`의 3000번 포트(팀원 IP만 허용, 계정 `admin`, 비밀번호는 `terraform output grafana_password_parameter`의 SSM 파라미터)로 접속하고, 대시보드는 `monitoring/grafana/zelo-overview.json`이 자동으로 로드된다. 아래 2~4번은 **로컬이나 수집기를 직접 구성할 때**의 방법이다.

2. `targets.example.json`을 `targets.json`으로 복사하고 앱 EC2의 **각 사설 IP:9292**를 넣는다. `prometheus.yml`과 같은 디렉터리에 마운트한다. Prometheus 데이터 디렉터리는 영속 볼륨에 저장한다.
3. 별도 수집기라면 Terraform의 `metrics_collector_security_group_id`에 동일 VPC 수집기 SG를 지정한다. 기본 null은 기존 접근 정책을 유지한다. 배포/apply는 별도 수행한다.
4. ASG 교체·증설 시 targets.json을 갱신한다. 이 구성(file discovery)은 EC2 자동 탐색이 아니다. 자동 탐색은 위 Terraform 수집기가 쓴다. 실험 중 서버 수를 고정하거나 대상 갱신을 운영 절차에 포함한다. ALB 주소 하나를 scrape하지 않는다.
5. 실험 전 각 서버에 정상 조회/채팅과 오류 시나리오를 한 번 실행하고 아래 검사기를 실행한다. Prometheus의 `up`이 모든 인스턴스에서 1인지 확인한다.

```bash
python3 monitoring/check_metrics.py http://APP_PRIVATE_IP:9292/actuator/prometheus
```

관리 엔드포인트는 VPC 내부에서 수집한다. targets.json과 실험 출력은 저장소에 넣지 않는다. Hikari 지표는 실제 데이터소스 사용 후 확인한다. `/actuator/metrics`는 현재 노출 설정에 없으므로 Prometheus 엔드포인트를 사용한다.

## 바로 사용할 PromQL

조회 P95(초, 전체 서버 합산):
```promql
histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{application="zelo",method="GET",uri="/api/chat/rooms/{roomId}/messages",status="200"}[1m])))
```
LLM 최종 성공 호출 P95:
```promql
histogram_quantile(0.95, sum by (le, operation) (rate(zelo_llm_call_seconds_bucket{outcome="SUCCESS"}[1m])))
```
실행 중 작업/실제 시작률:
```promql
sum(zelo_llm_inflight)
sum(rate(zelo_llm_execution_started_total[1m]))
sum(zelo_chat_inflight)
```
채팅 처리 결과(정상·대체·거절을 혼합하지 않는다):
```promql
sum by (result) (rate(zelo_chat_reply_seconds_count[1m]))
sum by (operation, status) (rate(zelo_chat_request_seconds_count[1m]))
```
최종 LLM 실패율(분모 0이면 데이터 없음으로 해석):
```promql
sum(rate(zelo_llm_call_seconds_count{outcome!="SUCCESS"}[1m])) / sum(rate(zelo_llm_call_seconds_count[1m]))
```
DB 점유·대기:
```promql
sum by (instance, pool) (hikaricp_connections_active)
sum by (instance, pool) (hikaricp_connections_pending)
rate(hikaricp_connections_timeout_total[1m])
```

서킷은 `resilience4j_circuitbreaker_state`를 instance/state별로 표시한다. 각 인스턴스는 독립 서킷이므로 상태를 평균 내지 않는다. 재시작은 process 시작 시각과 함께 표시한다. k6의 실제 전송·완료·누락 iteration과 HTTP 대역 수신 수를 같이 보관한다. 메트릭은 DB 메시지 중복·유실을 증명하지 않으므로 테스트 요청 식별자로 DB를 별도 대조한다.

히스토그램 버킷은 1ms~120s이며 이를 초과하는 관측값도 count/sum/+Inf에 포함된다. 장시간 요청의 P95가 범위를 벗어나면 버킷 상한을 조정한다. 서버별 P95를 평균 내지 않는다. 수집 시작 전에 발생한 첫 증가·scrape 사이 프로세스 종료는 복구할 수 없으므로 사전 수집과 종료 후 배출 시간을 확보한다.
