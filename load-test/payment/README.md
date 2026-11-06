# 결제 부하 테스트

| 구분 | 기준 | 결과 |
|---|---|---|
| **DAU 5만 (본 테스트)** | 피크 1시간 1만 명, 결제 분당 100세션 | 목표 통과, 안정 한계 × 150 (약 540 RPS), 첫 병목 Tomcat 스레드 |
| 예행연습 | DAU 1만, 결제 분당 5세션 | 목표 통과. 요청이 겹치지 않아 동시성은 확인 못 함 |

## 범위
- 결제수단 조회 `GET /api/payment-methods`
- 결제 확인 `POST /api/payments/{portoneId}/confirm`
- 등록 카드 결제 `POST /api/payments/{portoneId}/payment-method`
- 재결제 `POST /api/payments`
- 로그인은 측정에서 뺀다 (`kind:payment` 태그가 없는 요청)

## 파일
경로는 `load-test/payment/` 기준
| 파일 | 쓰는 테스트 | 내용 |
|---|---|---|
| `payment-session.js` | 공통 | 결제 1건의 흐름 (로그인 → 결제수단 조회 → 재결제 → 결제) |
| `payment-smoke.js` | 공통 | 스모크: VU 1, 1분 |
| `portone-latency.js` | 공통 | 포트원 테스트 채널 응답 시간 측정 (fake 지연 근거) |
| `payment-dau50k-load.js` | DAU 5만 | 부하: 분당 100세션, 예열 2분 + 5분 |
| `payment-dau50k-stress.js` | DAU 5만 | 스트레스: 분당 1,000세션, 예열 2분 + 5분 |
| `payment-dau50k-limit.js` | DAU 5만 | 한계: 목표 × 10·50·100·150·200·250, 단계마다 60초 |
| `payment-load.js` | 예행연습 | 부하: 분당 5세션, 5분 |
| `payment-stress.js` | 예행연습 | 스트레스: 분당 50세션, 5분 |
| `data/` | 공통 | 시드 주문 CSV. 커밋하지 않는다 |

## 포트원 fake 지연 (근거)
| 호출 | 측정 (테스트 채널, `portone-latency.js`) | 적용 |
|---|---|---|
| 결제 조회 | avg 63ms, p95 72ms | `--zelo.portone.fake-delay=63ms` |
| 빌링키 결제 | avg 773ms, p95 1.01s | `--zelo.portone.fake-billing-delay=773ms` |

- 측정할 때 시크릿·빌링키는 환경변수로만 넘긴다: `PORTONE_API_SECRET='...' STORE_ID='...' PAYMENT_ID='...' BILLING_KEY='...' k6 run load-test/payment/portone-latency.js`

---

# DAU 5만 (본 테스트)

## 목표와 구성
- 목표: 1만 명 중 40%(4,000명) 결제 → 4,000 ÷ 3,600초 × 1.5 = **분당 100세션** (결제 요청 약 3.5 RPS)
- 시드: 결제 대기 주문 10만 건 (사용자 20,001~25,000)
- 통합 테스트는 `--zelo.seed.load-test.payment-order-count=0`(AWS는 SSM `/zelo/dev/zelo.seed.load-test.payment-order-count`)으로 켠다. 결제수단만 넣고 결제 대기 주문은 넣지 않는다. 결제 대기 주문이 있으면 새 주문이 `ORDER_004`로 막힌다
- 예열 2분은 측정에서 뺀다. 시나리오마다 `ORDER_OFFSET`만큼 띄워 시드 주문이 겹치지 않게 한다
- 한계는 setup에서 미리 로그인하고 결제 요청만 보낸다
- 로그인: 회원 시드 규칙(#431)대로 id 끝자리 1~7은 이메일, 8·9·0은 소셜로 로그인한다. 예전 회원 시드(1~21,000 이메일)가 남은 DB에서는 사용자 시드가 건너뛰므로 DB를 비우고 다시 넣는다

## 실행
모든 명령은 저장소 루트에서 실행한다. 터미널 3개를 쓴다

**터미널 1: 앱** (서버가 켜진 채로 있어서 이 터미널은 앱 전용)
```bash
./gradlew bootRun --args='--spring.profiles.active=local,performance --server.port=8080 --zelo.llm.mode=fake --zelo.social.mode=fake --zelo.portone.mode=fake --zelo.portone.fake-delay=63ms --zelo.portone.fake-billing-delay=773ms'
```
- `performance` 프로필이 시드 켜기와 스케줄러 끄기를 함께 적용한다 (#414)
- 로그 `부하 테스트 결제 시드 저장 완료 orders=100000`이 나오면 준비 끝

**터미널 2: 서버 지표** (k6 실행 전에 켜고, 끝나면 Ctrl+C)
```bash
load-test/chat/record-metrics.sh docs/load-test/results/payment/dau50k/<단계>-server.csv
```

**터미널 3: 시드 CSV 뽑기 → k6** (한 번에 한 단계만)
```bash
docker compose exec -T postgres psql -U postgres -d zelotest -c "\copy (select o.id as order_id, o.user_id, p.portone_id, p.status from orders o join payments p on p.order_id = o.id where o.quote_id like 'LT-PAY-%' order by o.quote_id) to stdout with csv header" > load-test/payment/data/payment-orders.csv
k6 run --summary-export=docs/load-test/results/payment/dau50k/smoke-summary.json load-test/payment/payment-smoke.js
k6 run -e VUS=2 --summary-export=docs/load-test/results/payment/dau50k/load-summary.json load-test/payment/payment-dau50k-load.js
k6 run -e VUS=2 --summary-export=docs/load-test/results/payment/dau50k/stress-summary.json load-test/payment/payment-dau50k-stress.js
k6 run --summary-export=docs/load-test/results/payment/dau50k/limit-summary.json --out csv=docs/load-test/results/payment/dau50k/limit.csv load-test/payment/payment-dau50k-limit.js
```

**단계마다 재시작:** 스모크·부하·스트레스·한계 **각 단계 전에** 터미널 1의 앱을 다시 실행하고, 터미널 3에서 CSV를 다시 뽑는다. 결제한 주문은 다시 못 쓰고, 재결제는 시드를 넣고 30분 뒤 막힌다
- `limit.csv`는 크기가 커서 커밋하지 않는다

## 결과 (로컬, 2026-11-05)
조건: performance 프로필, **커넥션 풀 10** (#414 전), Tomcat 스레드 200, k6·서버·DB를 PC 한 대에서 실행
- 예전 회원 시드(1~21,000 이메일, 그 뒤 소셜)로 쟀다. 로그인은 측정에서 빼서 결제 결과에는 영향이 없고, 미리 로그인 시간만 달라진다

| 단계 | 결제 RPS | 실패 | 누락 세션 | p95 | Tomcat (최대) | Hikari (최대 / 대기) | 판정 |
|---|---|---|---|---|---|---|---|
| 스모크 (VU 1) | 3.9 | 0% | - | 890ms | 1 | 1 / 0 | 통과 |
| 부하 (목표) | 3.6 | 0% | 0 | 887ms | 2 | 1 / 0 | 통과 |
| 스트레스 (× 10) | 36 | 0% | 0 | 884ms | 10 | 3 / 0 | 무너지지 않음 |
| 한계 × 10 | 37 | 0% | 0 | 883ms | 9 | 1 / 0 | 안정 |
| 한계 × 50 | 178 | 0% | 0 | 883ms | 41 | 4 / 0 | 안정 |
| 한계 × 100 | 359 | 0% | 0 | 879ms | 85 | 8 / 0 | 안정 |
| **한계 × 150** | **538** | 0% | **0** | **887ms** | 133 | 10 / 0 | **안정 (마지막)** |
| 한계 × 200 | 715 | 0% | 28 | 1.15s | **200** | 10 / **67** | 느려짐 |
| 한계 × 250 | 약 660 | 0% | 827 | **2.03s** | 200 | 10 / 58 | 기준 초과 (15초 만에 중단) |

- 안정 판정은 실패율·p95와 함께 **누락 세션(`dropped_iterations`) 0**인지 본다. 누락이 있으면 k6가 다 보내지 못한 것이라 서버 용량으로 읽지 않는다. 누락 수는 `limit-summary.json`의 단계별 값이다
- × 250이 15초 만에 멈춘 이유: **측정 당시 스크립트**는 단계별 p95 2초 초과(10초 뒤부터 판정)에도 `abortOnFail`로 중단했다. **지금 스크립트**는 리뷰를 반영해 p95로는 멈추지 않고 에러율 1% 초과에만 중단한다. 그래서 지금 스크립트로 다시 돌리면 × 250도 60초를 다 돈다
- DB: 단계마다 PAID 수 = 세션 수 (부하 700, 스트레스 5,202, 한계 56,017), 음수 재고 0

| API | 부하 p95 | 한계 × 150 p95 | 한계 × 250 p95 | 구성 |
|---|---|---|---|---|
| 등록 카드 결제 | 890ms | 901ms | 2.32s | 빌링키 결제 773ms + 결제 조회 63ms + 서버 |
| 일반 결제 (confirm) | 105ms | 112ms | 1.56s | 결제 조회 63ms + 서버 |
| 재결제 | 11ms | - | - | 서버만 |
| 결제수단 조회 | 2.6ms | - | - | 서버만 |

## 결론
| 항목 | 내용 |
|---|---|
| 목표 | 분당 100세션에서 실패 0%, 누락 0, p95 887ms로 통과. 10배에서도 같다 |
| 안정 한계 | **× 150 = 약 540 RPS** (실패 0%, 누락 0, p95 887ms) |
| 무너지는 지점 | × 200부터 느려지고 누락이 생긴다. × 250에서 p95 2초 초과. 더 보내도 처리량이 715 → 660으로 줄었다 |
| 첫 병목 | **Tomcat 스레드 200개.** 포트원 응답을 기다리는 동안 스레드를 잡는다 (동시 요청 = 초당 세션 × 세션 시간, × 200: 333 × 0.6초 ≈ 200) |
| 다음 | 포트원이 느려지면 스레드가 다른 기능까지 막을 수 있다 → 벌크헤드·서킷 브레이커와 장애 테스트 |

---

<details>
<summary><b>예행연습 (DAU 1만, 2026-11-04)</b></summary>

## 목표와 구성
- 목표: 피크 2,000명 × 결제 20% × 2.3회 ÷ 7,200초 × 1.5 = 0.2 RPS = 분당 5세션
- 스모크 VU 1, 부하 분당 5세션, 스트레스 분당 50세션, 각 5분
- 조건: 커넥션 풀 10, 예열 없음

## 결과
| 단계 | 세션 | RPS | 실패 | p95 | Tomcat (최대) | Hikari (최대 / 대기) | 판정 |
|---|---|---|---|---|---|---|---|
| 스모크 (VU 1) | 110 | 3.9 | 0% | 872ms | 1 | 1 / 0 | 통과 |
| 부하 (목표) | 26 | 0.18 | 0% | 898ms | 1 | 1 / 0 | 통과 |
| 스트레스 (× 10) | 250 | 1.79 | 0% | 872ms | 1 | 1 / 0 | 무너지지 않음 |

- DB: 단계마다 PAID 수 = 세션 수
- p95는 등록 카드 결제(빌링키 773ms + 조회 63ms + 서버 약 36ms)다

## 결론
- 목표와 10배에서 실패 0%, p95 약 0.9초. 시간 대부분이 포트원 대기다
- 세션(평균 0.56초)이 간격(1.2초)보다 짧아 요청이 겹치지 않았다. 동시성 병목과 한계는 확인하지 못했다 → DAU 5만 테스트에서 예열과 한계 단계를 추가했다

</details>
