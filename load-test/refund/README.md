# 환불 부하 테스트

## 범위
- 환불 요청 `POST /api/orders/{orderId}/refund` (전액 / 부분)
- 서버 흐름: 주문별 락 → 환불 요청 저장·배송 보류 → 포트원 취소(트랜잭션 밖) → 환불 완료·재고 복원·결제/배송 취소 또는 보류 해제
- 로그인은 측정에서 뺀다 (`kind:refund` 태그가 없는 요청)

## 파일
| 파일 | 내용 |
|---|---|
| `refund-session.js` | 환불 1건의 흐름 (로그인 → 환불 요청) |
| `refund-smoke.js` | 스모크: VU 1, 1분 |
| `refund-load.js` | 부하: 100초에 1건, 5분 |
| `refund-stress.js` | 스트레스: 부하 × 10, 5분 |
| `portone-cancel-latency.js` | 포트원 테스트 채널 취소 응답 시간 측정 (fake-cancel-delay 근거) |
| `data/` | 시드 주문 CSV. 커밋하지 않는다 |

## 목표와 VU
- 목표: 0.01 RPS = 100초에 1건 (피크 2,000명 × 환불 2% × 1회 ÷ 7,200초 × 1.5)
- 스모크: VU 1 고정, 1분 → 환불 요청 p95 측정
- 부하: VU = 0.01 × 스모크 p95(초), 반올림 (최소 1), 5분
- 스트레스: 부하 × 10 (10초에 1건, VU × 10), 5분
- 부분 환불: 상품 2개 이상인 주문의 절반은 첫 상품만 환불 (가정값)

## 포트원 취소 응답 시간 (fake-cancel-delay 근거)
- 측정: `portone-cancel-latency.js`, 테스트 채널, 로컬, 2026-11-04
- 취소: avg 807ms, med 863ms, p95 1.07s (20회). 조회(63ms)보다 빌링키 결제(773ms)와 비슷하다
- 적용: `--zelo.portone.fake-cancel-delay=807ms`

측정 방법
1. 테스트 빌링키 준비: 포트원 콘솔(테스트 모드)에서 확인하거나 `http/payment-test/billing-key.html`로 발급
2. 실행 (시크릿·빌링키는 환경변수로만 넘긴다)
   ```bash
   PORTONE_API_SECRET='...' STORE_ID='...' BILLING_KEY='...' k6 run load-test/refund/portone-cancel-latency.js
   ```
3. `http_req_duration{name:cancelPayment}`의 avg를 위에 적는다

## 준비
1. 앱 실행 (사용자·상품 시드가 이미 있으면 한 번만, 없으면 DB를 비우고 두 번)
   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=local,performance --server.port=8080 --zelo.llm.mode=fake --zelo.social.mode=fake --zelo.portone.mode=fake --zelo.portone.fake-delay=63ms --zelo.portone.fake-billing-delay=773ms --zelo.portone.fake-cancel-delay=807ms --zelo.seed.enabled=true --zelo.seed.load-test.enabled=true --zelo.delivery.scheduler.enabled=false --zelo.order.expiration.scheduler.enabled=false --jwt.access-expiration=3600000'
   ```
   배송 스케줄러는 반드시 끈다 (배송 준비 10분 뒤 자동 출고되면 환불이 막힌다)
2. 시드 주문 CSV 뽑기 (시드를 새로 넣을 때마다)
   ```bash
   mkdir -p load-test/refund/data && docker compose exec -T postgres psql -U postgres -d zelotest -c "\copy (select o.id as order_id, o.user_id, string_agg(oi.id || ':' || oi.quantity, ';' order by oi.id) as items from orders o join order_items oi on oi.order_id = o.id where o.quote_id like 'LT-REF-%' group by o.id, o.user_id, o.quote_id order by o.quote_id) to stdout with csv header" > load-test/refund/data/refund-orders.csv
   ```

## 실행
단계마다 앱 재시작 → CSV 다시 뽑기 → 실행
```bash
mkdir -p docs/load-test/results/refund
k6 run --summary-export=docs/load-test/results/refund/smoke-summary.json load-test/refund/refund-smoke.js
k6 run -e VUS=1 --summary-export=docs/load-test/results/refund/load-summary.json load-test/refund/refund-load.js
k6 run -e VUS=1 --summary-export=docs/load-test/results/refund/stress-summary.json load-test/refund/refund-stress.js
```
테스트 중에는 서버 지표를 같이 기록한다: `load-test/record-metrics.sh docs/load-test/results/refund/<단계>-server.csv`

## 볼 지표
- k6:
    - `http_req_duration{kind:refund}` p95, p99
    - `{name:fullRefund}`, `{name:partialRefund}`
    - `http_req_failed{kind:refund}`
    - `checks`
    - `dropped_iterations`
- 서버:
    - Hikari `hikaricp_connections_active` / `pending`
    - Tomcat `tomcat_threads_busy_threads`
- DB 대조:
    - `refunds` 상태별 건수
    - 전액 환불 주문의 `deliveries` CANCELED, `payments` CANCELED
    - 부분 환불 주문의 배송 `on_hold = false`

## 결과 (예행연습, 로컬, 2026-11-04)
조건: performance 프로필, 포트원 fake (`fake-cancel-delay=807ms`), 환불 대기(결제 완료 + 배송 준비) 주문 1만 건. 시간·p95 기준(1초)은 임시값

### 요약
| 단계 | 조건 | 환불 요청 | RPS | 실패 | checks | p95 | 판정 |
|---|---|---|---|---|---|---|---|
| 스모크 | VU 1, 1분, 쉬지 않고 | 71 | 1.17 | 0% | 100% (142/142) | 858ms | 통과 |
| 부하 | 100초에 1건 (목표 0.01 RPS), VU 1, 5분 | 4 | 0.013 | 0% | 100% (8/8) | 1.07s | p95 기준 초과 (첫 요청 1건) |
| 스트레스 | 10초에 1건 (목표 × 10), VU 10, 5분 | 30 | 0.1 | 0% | 100% (60/60) | 867ms | 무너지지 않음 |

### 환불 요청 응답 시간 (`kind:refund`)
| 단계 | min | avg | med | p90 | p95 | max |
|---|---|---|---|---|---|---|
| 스모크 | 832ms | 846ms | 842ms | 853ms | 858ms | 1.04s |
| 부하 | 846ms | 915ms | 854ms | 1.03s | 1.07s | 1.10s |
| 스트레스 | 846ms | 864ms | 857ms | 866ms | 867ms | 1.04s |

- 포트원 취소 지연 807ms + 서버 처리 약 40ms (락, 트랜잭션 2번, 재고 복원, 결제·배송 취소)
- 부하의 p95 초과: 4건 중 앱을 켠 뒤 첫 요청 1건만 1.1초, 나머지 3건은 약 0.85초
  - DB의 환불 요청 → 완료 시간은 850 / 815 / 817 / 815ms로 포트원 구간은 같다. 첫 요청만 예열(첫 조회·락·JIT)로 약 250ms 더 걸림
  - 표본이 4건이라 p95가 사실상 최댓값이다
- 각 단계의 max(1.04~1.10s)는 앱을 켠 뒤 첫 요청으로 보인다 (예열 전)

### 서버·DB
| 단계 | Tomcat 바쁜 스레드 (최대) | Hikari 대기 | 놓친 세션 | 전액 환불 | 부분 환불 |
|---|---|---|---|---|---|
| 스모크 | 1 | 0 | - | 57 | 14 |
| 부하 | 1 | 0 | 0 | 3 | 1 |
| 스트레스 | 1 | 0 | 0 | 24 | 6 |

| 구분 | 환불 | 주문 | 결제 | 배송 |
|---|---|---|---|---|
| 전액 환불 | COMPLETED | CANCELED | CANCELED | CANCELED |
| 부분 환불 | COMPLETED | PAID | PAID | READY (보류 해제) |

- 세 단계 모두 DB 상태가 위 표와 같았다
- 부분 환불 비율은 약 20%로 가정(약 35%)보다 낮다. 주문 id 짝수·상품 2개 이상 조건이 시드 순서와 겹쳐서다
- 부하 VU = 0.01 × 0.858 = 0.009 → 1 (최소 1)

## 결론
| 항목 | 내용 |
|---|---|
| 버티는가 | 목표(0.01 RPS)와 그 10배(0.1 RPS)에서 실패 0%, 환불·주문·결제·배송 상태가 모두 맞다 |
| 시간 구성 | 대부분 포트원 취소 대기(807ms). 서버 처리는 약 40ms |
| 한계 | 스트레스에서도 동시에 일한 VU는 최대 1명. 환불 1건(0.86초)이 간격(10초)보다 짧아 요청이 겹치지 않았다. 동시 환불 병목과 한계 RPS는 확인하지 못함. 부하는 5분에 4건이라 p95를 믿기 어렵다 |
| 다음 | 측정 전에 예열 요청을 보낸다. 응답 시간은 표본이 많은 스모크로 판단하고, 한계는 동시 요청이 생기는 수준(예: 초당 1 → 2 → 5건)까지 올려 본다 |
