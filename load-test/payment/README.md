# 결제 부하 테스트

## 범위
- 결제수단 조회 `GET /api/payment-methods`
- 결제 확인 `POST /api/payments/{portoneId}/confirm`
- 등록 카드 결제 `POST /api/payments/{portoneId}/payment-method`
- 재결제 `POST /api/payments`
- 로그인은 측정에서 뺀다 (`kind:payment` 태그가 없는 요청)

## 파일
| 파일 | 내용 |
|---|---|
| `payment-session.js` | 결제 1건의 흐름 (로그인 → 결제수단 조회 → 재결제 → 결제) |
| `payment-smoke.js` | 스모크: VU 1, 1분 |
| `payment-load.js` | 부하: 세션 분당 5개, 5분 |
| `payment-stress.js` | 스트레스: 부하 × 10, 5분 |
| `portone-latency.js` | 포트원 테스트 채널 응답 시간 측정 (fake-delay 근거) |
| `data/` | 시드 주문 CSV. 커밋하지 않는다 |

## 목표와 VU
- 목표: 0.2 RPS = 세션 분당 5개 (피크 2,000명 × 결제 20% × 2.3회 ÷ 7,200초 × 1.5)
- 스모크: VU 1 고정, 1분 → 결제 요청 p95 측정
- 부하: VU = 0.2 × 스모크 p95(초), 반올림, 5분
- 스트레스: 부하 × 10 (세션 분당 50개, VU × 10), 5분

## 포트원 응답 시간 (fake-delay 근거)
- 측정: `portone-latency.js`, 테스트 채널, 로컬, 2026-11-04
- 결제 조회: avg 63ms, p95 72ms (31회)
- 빌링키 결제: avg 773ms, p95 1.01s (21회). PG사 승인까지 거쳐서 조회보다 크게 느리다
- 적용 (팀 기준대로 평균): `--zelo.portone.fake-delay=63ms --zelo.portone.fake-billing-delay=773ms`
- 취소(`cancelPayment`)는 측정하지 않아 조회 값을 쓴다. 환불 테스트 때 따로 잰다

측정 방법 (DB를 비우기 전에 한다. 비우면 테스트 결제와 빌링키가 사라진다)
1. 결제된 테스트 결제 ID: `select portone_id from payments where status = 'PAID' order by id desc limit 1;`
2. 테스트 빌링키: `select billing_key from payment_methods where billing_key not like 'fake-%' order by id desc limit 1;` (없으면 `http/payment-test/billing-key.html`로 발급)
3. 실행 (시크릿·빌링키는 환경변수로만 넘긴다)
   ```bash
   PORTONE_API_SECRET='...' STORE_ID='...' PAYMENT_ID='...' BILLING_KEY='...' k6 run load-test/payment/portone-latency.js
   ```
4. `http_req_duration{name:getPayment}`, `{name:billingPay}`의 avg를 위에 적는다

## 준비
1. DB 비우기: `docker compose down -v` → `docker compose up -d --wait`
2. 앱을 두 번 실행한다 (1번째: 사용자·상품, 2번째: 결제 대기 주문 10만 건)
   ```bash
   JWT_SECRET=... OPENAI_API_KEY=fake ./gradlew bootRun --args='--spring.profiles.active=performance --zelo.portone.mode=fake --zelo.portone.fake-delay=63ms --zelo.portone.fake-billing-delay=773ms --zelo.seed.enabled=true --zelo.seed.load-test.enabled=true --zelo.delivery.scheduler.enabled=false --zelo.order.expiration.scheduler.enabled=false --jwt.access-expiration=3600000'
   ```
3. 시드 주문 CSV 뽑기 (시드를 새로 넣을 때마다)
   ```bash
   mkdir -p load-test/payment/data && docker compose exec -T postgres psql -U postgres -d zelotest -c "\copy (select o.id as order_id, o.user_id, p.portone_id, p.status from orders o join payments p on p.order_id = o.id where o.quote_id like 'LT-PAY-%' order by o.quote_id) to stdout with csv header" > load-test/payment/data/payment-orders.csv
   ```

## 실행
단계마다 앱 재시작 → CSV 다시 뽑기 → 실행. 시드를 넣고 30분 안에 끝낸다 (재결제는 주문 생성 30분 뒤 막힘)
```bash
mkdir -p docs/load-test/results/payment
k6 run --summary-export=docs/load-test/results/payment/smoke-summary.json load-test/payment/payment-smoke.js
k6 run -e VUS=1 --summary-export=docs/load-test/results/payment/load-summary.json load-test/payment/payment-load.js
k6 run -e VUS=1 --summary-export=docs/load-test/results/payment/stress-summary.json load-test/payment/payment-stress.js
```

## 볼 지표
- k6:
  - `http_req_duration{kind:payment}` p95, p99
  - `http_req_failed{kind:payment}`
  - `checks`
  - `dropped_iterations` (VU가 모자라 못 보낸 세션)
- 서버 (`http://localhost:9292/actuator/prometheus`):
  - DB 커넥션 `hikaricp_connections_active` / `hikaricp_connections_pending`
  - Tomcat `tomcat_threads_busy_threads`
- 결제 결과: `sum by (status) (rate(zelo_payment_result_total[1m]))`
- 결제 실패 코드: `sum by (code) (rate(zelo_api_error_total{code=~"PAYMENT.*|PG.*"}[1m]))`

## 결과 (예행연습, 로컬, 2026-11-04)
조건: performance 프로필, 포트원 fake (`fake-delay=63ms`, `fake-billing-delay=773ms`), 결제 대기 주문 10만 건. 시간·p95 기준(1초)은 임시값

### 요약
| 단계 | 조건 | 세션 | 결제 요청 | RPS | 실패 | checks | p95 | 판정 |
|---|---|---|---|---|---|---|---|---|
| 스모크 | VU 1, 1분, 쉬지 않고 | 110 | 235 | 3.9 | 0% | 100% (345/345) | 872ms | 통과 |
| 부하 | 분당 5세션 (목표 0.2 RPS), VU 1, 5분 | 26 | 55 | 0.18 | 0% | 100% (81/81) | 898ms | 통과 |
| 스트레스 | 분당 50세션 (목표 × 10), VU 10, 5분 | 250 | 536 | 1.79 | 0% | 100% (786/786) | 872ms | 무너지지 않음 |

### 결제 요청 응답 시간 (`kind:payment`)
| 단계 | avg | med | p90 | p95 | max |
|---|---|---|---|---|---|
| 스모크 | 229ms | 11ms | 870ms | 872ms | 895ms |
| 부하 | 242ms | 23ms | 896ms | 898ms | 902ms |
| 스트레스 | 227ms | 11ms | 870ms | 872ms | 901ms |

- p95는 등록 카드 결제: 빌링키 결제 지연 773ms + 결제 조회 63ms + 서버 처리 약 36ms
- 결제수단 조회·재결제처럼 포트원 대기가 없는 요청은 수십 ms 이하
- 세 단계의 응답 시간이 같다. 목표와 그 10배에서 느려지지 않음

### 서버·DB
| 단계 | Tomcat 바쁜 스레드 (최대) | Hikari 사용 (최대) | Hikari 대기 | 놓친 세션 | DB: 시드 주문 PAID |
|---|---|---|---|---|---|
| 스모크 | 1 | 1 | 0 | - | 110 (세션 수와 같음) |
| 부하 | 1 | 1 | 0 | 0 | 26 (세션 수와 같음) |
| 스트레스 | 1 | 1 | 0 | 0 | 250 (세션 수와 같음) |

- 스모크 check별: 로그인 110/110, 결제수단 조회 110/110, 재결제 15/15, 결제 PAID 110/110
- 부하 RPS가 0.2보다 낮은 이유: 세션당 2.3회 가정인데 재결제가 26회 중 3회라 세션당 약 2.1회
- 부하 VU = 0.2 × 0.872 = 0.17 → 1 (최소 1)

## 결론
| 항목 | 내용 |
|---|---|
| 버티는가 | 목표(0.2 RPS)와 그 10배(2 RPS)에서 실패 0%, p95 약 0.9초 |
| 시간 구성 | 대부분 포트원 대기(빌링키 결제 773ms). 서버 처리는 수십 ms |
| 한계 | 스트레스에서 VU 10명을 준비했지만 동시에 일한 VU는 최대 1명. 세션 1회(평균 0.56초)가 간격(1.2초)보다 짧아 요청이 겹치지 않았다. 락 경합·커넥션 풀 고갈 같은 동시성 병목과 한계 RPS는 확인하지 못함 |
| 다음 | 동시 요청이 생기는 수준까지 올린다 (예: 세션 분당 50 → 100 → 200 → 400, VU 넉넉히, Hikari 대기·Tomcat 스레드 확인). 결과를 API별(`name` 태그)로 나눠 본다 |
