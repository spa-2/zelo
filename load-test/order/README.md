# 주문 부하 테스트

## 범위
- 주문 확인 `POST /api/orders/confirmations`
- 주문 생성 `POST /api/orders` (사용자별 락, 상품 검증, 재고 선점, 결제 READY 생성)
- 주문 상세 `GET /api/orders/{orderId}`
- 로그인은 측정에서 뺀다 (`kind:order` 태그가 없는 요청)
- 외부 API 호출이 없어 응답 시간이 서버 처리 시간이다

## 파일
| 파일 | 내용 |
|---|---|
| `order-session.js` | 주문 1건의 흐름 (로그인 → 주문 확인 → 주문 생성 → 주문 상세) |
| `order-smoke.js` | 스모크: VU 1, 1분 |
| `order-load.js` | 부하: 세션 분당 5개, 5분 |
| `order-stress.js` | 스트레스: 부하 × 10, 5분 |

## 목표와 VU
- 목표: 0.25 RPS = 세션 분당 5개 (피크 2,000명 × 주문 20% × 3회 ÷ 7,200초 × 1.5)
- 스모크: VU 1 고정, 1분 → 주문 요청 p95 측정
- 부하: VU = 0.25 × 스모크 p95(초), 반올림 (최소 1), 5분
- 스트레스: 부하 × 10 (세션 분당 50개, VU × 10), 5분

## 시드
- 사용자 15,001 ~ 20,000 (전부 이메일 로그인, `load{id}@test.com` / `Zelo1234!`)
- 사용자마다 채팅방 1개 (ID = 90,000,000 + 사용자 ID)와 Redis 견적 1개 (`LT-ORD-{사용자 ID 7자리}`, TTL 3시간)
- 결제 대기 주문은 사용자당 1건이라 1회 테스트에 최대 5,000세션

## 준비
1. 앱 실행 (사용자·상품 시드가 이미 있으면 한 번만, 없으면 DB를 비우고 두 번)
   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=local,performance --server.port=8080 --zelo.llm.mode=fake --zelo.social.mode=fake --zelo.portone.mode=fake --zelo.portone.fake-delay=63ms --zelo.portone.fake-billing-delay=773ms --zelo.portone.fake-cancel-delay=807ms --zelo.seed.enabled=true --zelo.seed.load-test.enabled=true --zelo.delivery.scheduler.enabled=false --zelo.order.expiration.scheduler.enabled=false --jwt.access-expiration=3600000'
   ```
2. 로그에 `부하 테스트 주문 시드 저장 완료 quotes=5000`이 나오는지 확인

## 실행
단계마다 앱을 재시작한다 (지난 주문 삭제 + 선점 재고 복원 + 견적 다시 저장)
```bash
mkdir -p docs/load-test/results/order
k6 run --summary-export=docs/load-test/results/order/smoke-summary.json load-test/order/order-smoke.js
k6 run -e VUS=1 --summary-export=docs/load-test/results/order/load-summary.json load-test/order/order-load.js
k6 run -e VUS=1 --summary-export=docs/load-test/results/order/stress-summary.json load-test/order/order-stress.js
```
테스트 중에는 서버 지표를 같이 기록한다: `load-test/chat/record-metrics.sh docs/load-test/results/order/<단계>-server.csv`

## 볼 지표
- k6:
  - `http_req_duration{kind:order}` p95, p99
  - API별: `{name:createConfirmation}`, `{name:createOrder}`, `{name:getOrderDetail}`
  - `http_req_failed{kind:order}`
  - `checks`
  - `dropped_iterations`
- 서버: Hikari `hikaricp_connections_active` / `pending`, Tomcat `tomcat_threads_busy_threads`
- DB 대조: `LT-ORD-` 주문 수 = 세션 수, 주문 상태 `PAYMENT_PENDING`, 결제 `READY`, 주문 상품 `RESERVED`

## 결과 (로컬, 2026-11-04)
조건: performance 프로필, 외부 API 호출 없음, 주문 시드(채팅방 + 견적) 5,000명, 커넥션 풀 최대 10. 시간·p95 기준(1초)은 임시값. 정합성 대사 스케줄러는 켜져 있었다 (대상 0건)

### 요약
| 단계 | 조건 | 세션 | 주문 요청 | RPS | 실패 | checks | p95 | 판정 |
|---|---|---|---|---|---|---|---|---|
| 스모크 | VU 1, 1분, 쉬지 않고 | 578 | 1,734 | 28.9 | 0% | 100% (2,890/2,890) | 24.0ms | 통과 |
| 부하 | 분당 5세션 (목표 0.25 RPS), VU 1, 5분 | 26 | 78 | 0.26 | 0% | 100% (130/130) | 33.6ms | 통과 |
| 스트레스 | 분당 50세션 (목표 × 10), VU 10, 5분 | 251 | 753 | 2.51 | 0% | 100% (1,255/1,255) | 21.3ms | 무너지지 않음 |

### 주문 요청 응답 시간 (`kind:order`)
| 단계 | avg | med | p90 | p95 | max |
|---|---|---|---|---|---|
| 스모크 | 11.5ms | 7.5ms | 21.9ms | 24.0ms | 42.8ms |
| 부하 | 17.3ms | 9.7ms | 27.5ms | 33.6ms | 125.8ms |
| 스트레스 | 10.4ms | 6.3ms | 19.2ms | 21.3ms | 135.6ms |

### API별 p95
| API | 스모크 | 부하 | 스트레스 |
|---|---|---|---|
| 주문 확인 | 10.7ms | 15.1ms | 9.5ms |
| 주문 생성 | 27.4ms | 39.4ms | 25.9ms |
| 주문 상세 | 9.1ms | 11.8ms | 9.0ms |

- 외부 대기가 없어 응답 시간이 곧 서버 처리 시간이다. 주문 생성(사용자 락, 상품 검증, 재고 선점, 결제 생성)이 가장 길다
- 부하가 조금 느린 이유: 요청이 12초에 1번이라 사이에 서버가 쉬어 덜 데워진 상태로 처리한다
- 부하·스트레스의 max(126~136ms)는 앱 재시작 뒤 첫 세션(예열 전)이다
- 전체 `http_req_duration` p95(약 70ms)는 로그인(비밀번호 확인) 때문이다. 로그인은 측정에서 뺀다

### 서버·DB
| 단계 | Tomcat 바쁜 스레드 (최대) | Hikari 사용 (최대) | Hikari 대기 | 놓친 세션 | DB: 주문 `PAYMENT_PENDING` + 결제 `READY` |
|---|---|---|---|---|---|
| 스모크 | 1 | 1 | 0 | - | 578 (사용자 15,001~15,578) |
| 부하 | 1 | 1 | 0 | 0 | 26 (사용자 15,001~15,026) |
| 스트레스 | 1 | 1 | 0 | 0 | 251 (사용자 15,001~15,251) |

- 세 단계 모두 DB 주문 수가 k6 세션 수와 같고, 주문 상품은 `RESERVED`(재고 선점)였다
- 스모크 전에 확인용으로 사용자 20,000번 주문 1건을 직접 만들었다 (스모크 DB 579건 중 1건)
- 부하 VU = 0.25 × 0.024 = 0.006 → 1 (최소 1)

## 결론
| 항목 | 내용 |
|---|---|
| 버티는가 | 목표(0.25 RPS)와 그 10배(2.5 RPS)에서 실패 0%, 주문 요청 p95 21~34ms |
| 시간 구성 | 서버 처리만. 주문 생성이 가장 길다 (p95 26~39ms) |
| 비교 | 결제(p95 872ms)·환불(858ms)은 포트원 대기가 대부분이고, 주문은 서버 처리만이라 30배 넘게 빠르다 |
| 한계 | 스트레스에서도 동시에 일한 VU는 최대 1명. 세션 1회(약 0.1초)가 간격(1.2초)보다 짧아 요청이 겹치지 않았다. 동시성 병목(사용자 락, 재고 선점 경합, 커넥션 풀)과 한계 RPS는 확인하지 못함. 재시작 직후 첫 세션이 느리다 (예열 없음) |
| 다음 | 측정 전 예열, 정합성 대사 스케줄러 끄기, 동시 요청이 생기는 수준까지 단계 상승, AWS 테스트 때 1년 치 이력 데이터 추가 |
