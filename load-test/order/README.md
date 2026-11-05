# 주문 부하 테스트

| 구분 | 기준 | 결과 |
|---|---|---|
| **DAU 5만 (본 테스트)** | 피크 1시간 1만 명, 주문 분당 100세션 | 목표 통과, × 100(약 500 RPS)까지 무너지지 않음 |
| 예행연습 | DAU 1만, 주문 분당 5세션 | 목표 통과. 요청이 겹치지 않아 동시성은 확인 못 함 |

## 범위
- 주문 확인 `POST /api/orders/confirmations`
- 주문 생성 `POST /api/orders` (사용자별 락, 상품 검증, 재고 선점, 결제 READY 생성)
- 주문 상세 `GET /api/orders/{orderId}`
- 로그인은 측정에서 뺀다 (`kind:order` 태그가 없는 요청). 외부 API 호출이 없어 응답 시간이 서버 처리 시간이다

## 파일
경로는 `load-test/order/` 기준
| 파일 | 쓰는 테스트 | 내용 |
|---|---|---|
| `practice/order-session.js` | 공통 | 주문 1건의 흐름 (로그인 → 주문 확인 → 주문 생성 → 주문 상세) |
| `practice/order-smoke.js` | 공통 | 스모크: VU 1, 1분 |
| `50kdau/order-dau50k-load.js` | DAU 5만 | 부하: 분당 100세션, 예열 2분 + 5분 |
| `50kdau/order-dau50k-stress.js` | DAU 5만 | 스트레스: 분당 1,000세션, 예열 2분 + 5분 |
| `50kdau/order-dau50k-limit.js` | DAU 5만 | 한계: 목표 × 10·30·60·100, 단계마다 20초 |
| `practice/order-load.js` | 예행연습 | 부하: 분당 5세션, 5분 |
| `practice/order-stress.js` | 예행연습 | 스트레스: 분당 50세션, 5분 |

---

# DAU 5만 (본 테스트)

## 목표와 구성
- 목표: 1만 명 중 40%(4,000명) 주문 → 4,000 ÷ 3,600초 × 1.5 = **분당 100세션** (주문 요청 약 5 RPS)
- 사용자: 15,001~20,000(이메일) + 27,001~30,000(소셜) **8,000명**. 주문은 사용자당 1건이라 범위를 늘렸다
- 예열 2분은 측정에서 뺀다. 시나리오마다 `USER_OFFSET`만큼 띄워 사용자가 겹치지 않게 한다
- 한계는 setup에서 미리 로그인하고 주문 요청만 보낸다. 사용자가 8,000명이라 단계를 20초로 둔다

## 실행
모든 명령은 저장소 루트에서 실행한다. 터미널 3개를 쓴다

**터미널 1: 앱** (서버가 켜진 채로 있어서 이 터미널은 앱 전용)
```bash
./gradlew bootRun --args='--spring.profiles.active=local,performance --server.port=8080 --zelo.llm.mode=fake --zelo.social.mode=fake --zelo.portone.mode=fake'
```
- `performance` 프로필이 시드 켜기와 스케줄러 끄기를 함께 적용한다 (#414)
- 로그 `부하 테스트 주문 시드 저장 완료 quotes=8000`이 나오면 준비 끝

**터미널 2: 서버 지표** (k6 실행 전에 켜고, 끝나면 Ctrl+C)
```bash
load-test/chat/record-metrics.sh docs/load-test/results/order/dau50k/<단계>-server.csv
```

**터미널 3: k6** (한 번에 한 단계만)
```bash
k6 run --summary-export=docs/load-test/results/order/dau50k/smoke-summary.json load-test/order/practice/order-smoke.js
k6 run -e VUS=2 --summary-export=docs/load-test/results/order/dau50k/load-summary.json load-test/order/50kdau/order-dau50k-load.js
k6 run -e VUS=2 --summary-export=docs/load-test/results/order/dau50k/stress-summary.json load-test/order/50kdau/order-dau50k-stress.js
k6 run --summary-export=docs/load-test/results/order/dau50k/limit-summary.json --out csv=docs/load-test/results/order/dau50k/limit.csv load-test/order/50kdau/order-dau50k-limit.js
```

**단계마다 재시작:** 스모크·부하·스트레스·한계 **각 단계 전에** 터미널 1의 앱을 Ctrl+C로 끄고 다시 실행한다. 견적이 사용자당 1개라 한 번 주문하면 다시 못 쓰고, 재시작하면 시드가 다시 들어간다
- `limit.csv`는 크기가 커서 커밋하지 않는다

## 결과 (로컬, 2026-11-05)
조건: performance 프로필 (커넥션 풀 30, 스케줄러 끔), Tomcat 스레드 200, k6·서버·DB를 PC 한 대에서 실행

| 단계 | 주문 RPS | 실패 | 누락 세션 | p95 | Tomcat (최대) | Hikari (최대 / 대기) | 판정 |
|---|---|---|---|---|---|---|---|
| 스모크 (VU 1) | 31 | 0% | - | 20.5ms | 1 | 1 / 0 | 통과 |
| 부하 (목표) | 5 | 0% | 0 | 18.4ms | 1 | 1 / 0 | 통과 |
| 스트레스 (× 10) | 50 | 0% | 0 | 17.2ms | 18 | 2 / 0 | 무너지지 않음 |
| 한계 × 10 | 51 | 0% | 0 | 18.9ms | 2 | 1 / 0 | 안정 |
| 한계 × 30 | 150 | 0% | 0 | 16.8ms | 1 | 2 / 0 | 안정 |
| 한계 × 60 | 300 | 0% | 0 | 16.5ms | 3 | 2 / 0 | 안정 |
| **한계 × 100** | **501** | 0% | 0 | **17.0ms** | 5 | 5 / 0 | **안정 (최대로 올린 단계)** |

- 안정 판정은 실패율·p95와 함께 **누락 세션(`dropped_iterations`) 0**인지 본다. 누락이 있으면 k6가 다 보내지 못한 것이라 서버 용량으로 읽지 않는다

| API | 부하 p95 | 스트레스 p95 | 한계 × 100 p95 |
|---|---|---|---|
| 주문 확인 | 6.3ms | 5.7ms | 5.5ms |
| 주문 생성 | 19.5ms | 17.8ms | 18.8ms |
| 주문 상세 | 5.3ms | 5.2ms | 5.8ms |

- DB: 단계마다 주문 수 = 세션 수 (부하 701, 스트레스 5,202, 한계 6,682). 주문 PAYMENT_PENDING, 결제 READY, 주문 상품 RESERVED, 음수 재고 0
- 스트레스의 스레드 18개는 대부분 소셜 로그인 대기(0.9초)다

## 결론
| 항목 | 내용 |
|---|---|
| 목표 | 분당 100세션에서 실패 0%, p95 18ms로 통과 |
| 한계 | **× 100(약 500 RPS)까지 무너지지 않음.** 사용자가 8,000명이라 더 올리지 못했다 |
| 자원 | × 100에서도 스레드 5개, 커넥션 5개. 요청이 약 9ms에 끝나 동시 요청이 적다 (501 × 0.009초 ≈ 5) |
| 결제·환불과 차이 | 외부 대기가 없어 같은 RPS에서 스레드를 약 100분의 1만 쓴다. 한계는 CPU·DB 처리량일 가능성이 크다 |
| 참고 | 한계 테스트의 미리 로그인이 약 9분 걸렸다 (이메일 로그인 5,000명의 BCrypt) |

---

<details>
<summary><b>예행연습 (DAU 1만, 2026-11-04)</b></summary>

## 목표와 구성
- 목표: 피크 2,000명 × 주문 20% × 3회 ÷ 7,200초 × 1.5 = 0.25 RPS = 분당 5세션
- 사용자 15,001~20,000 (5,000명). 스모크 VU 1, 부하 분당 5세션, 스트레스 분당 50세션, 각 5분
- 조건: 커넥션 풀 10, 예열 없음, 정합성 대사 스케줄러 켜짐 (대상 0건)

## 결과
| 단계 | 세션 | RPS | 실패 | p95 | Tomcat (최대) | Hikari (최대 / 대기) | 판정 |
|---|---|---|---|---|---|---|---|
| 스모크 (VU 1) | 578 | 28.9 | 0% | 24.0ms | 1 | 1 / 0 | 통과 |
| 부하 (목표) | 26 | 0.26 | 0% | 33.6ms | 1 | 1 / 0 | 통과 |
| 스트레스 (× 10) | 251 | 2.51 | 0% | 21.3ms | 1 | 1 / 0 | 무너지지 않음 |

- DB: 단계마다 주문 수 = 세션 수, 주문 상품 RESERVED
- 부하·스트레스의 max(126~136ms)는 재시작 뒤 첫 세션이다 (예열 없음)

## 결론
- 목표와 10배에서 실패 0%, p95 21~34ms
- 세션(약 0.1초)이 간격(1.2초)보다 짧아 요청이 겹치지 않았다. 동시성 병목과 한계는 확인하지 못했다 → DAU 5만 테스트에서 예열, 스케줄러 끄기, 한계 단계를 추가했다

</details>
