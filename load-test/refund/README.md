# 환불 부하 테스트

| 구분 | 기준 | 결과 |
|---|---|---|
| **DAU 5만 (본 테스트)** | 결제 4,000명 중 50% 환불, 분당 50세션 | 목표 통과, 안정 한계 × 250 (초당 약 208건), 첫 병목 Tomcat 스레드 |
| 예행연습 | DAU 1만, 100초에 1건 | 목표 통과. 요청이 겹치지 않아 동시성은 확인 못 함 |

## 범위
- 환불 요청 `POST /api/orders/{orderId}/refund` (전액 / 부분)
- 서버 흐름: 주문별 락 → 환불 요청 저장·배송 보류 → 포트원 취소(트랜잭션 밖) → 환불 완료·재고 복원·결제/배송 취소 또는 보류 해제
- 로그인은 측정에서 뺀다 (`kind:refund` 태그가 없는 요청)
- 부분 환불: 상품 2개 이상인 주문의 절반은 첫 상품만 환불 (가정값)

## 파일
경로는 `load-test/refund/` 기준
| 파일 | 쓰는 테스트 | 내용 |
|---|---|---|
| `refund-session.js` | 공통 | 환불 1건의 흐름 (로그인 → 환불 요청) |
| `refund-smoke.js` | 공통 | 스모크: VU 1, 1분 |
| `portone-cancel-latency.js` | 공통 | 포트원 테스트 채널 취소 응답 시간 측정 (fake 지연 근거) |
| `refund-dau50k-load.js` | DAU 5만 | 부하: 분당 50세션, 예열 2분 + 5분 |
| `refund-dau50k-stress.js` | DAU 5만 | 스트레스: 분당 500세션, 예열 2분 + 5분 |
| `refund-dau50k-limit.js` | DAU 5만 | 한계: 목표 × 10·50·100·150·200·250·300, 단계마다 60초 |
| `refund-load.js` | 예행연습 | 부하: 100초에 1건, 5분 |
| `refund-stress.js` | 예행연습 | 스트레스: 10초에 1건, 5분 |
| `data/` | 공통 | 시드 주문 CSV. 커밋하지 않는다 |

## 포트원 fake 지연 (근거)
| 호출 | 측정 (테스트 채널, `portone-cancel-latency.js`) | 적용 |
|---|---|---|
| 결제 취소 | avg 807ms, p95 1.07s | `--zelo.portone.fake-cancel-delay=807ms` |

- 측정할 때 시크릿·빌링키는 환경변수로만 넘긴다: `PORTONE_API_SECRET='...' STORE_ID='...' BILLING_KEY='...' k6 run load-test/refund/portone-cancel-latency.js`

---

# DAU 5만 (본 테스트)

## 목표와 구성
- 목표: 결제 4,000명 중 50%(2,000명) 환불 → 2,000 ÷ 3,600초 × 1.5 = **분당 50세션** (환불 요청 약 0.83 RPS)
- 시드: 환불 대기 주문 **6만 건** (사용자 25,001~27,000, 이메일·소셜 섞임). 한계 테스트 전 단계를 한 번에 돌리려고 1만 → 6만으로 늘렸다
- 예열 2분은 측정에서 뺀다. 시나리오마다 `ORDER_OFFSET`만큼 띄워 시드 주문이 겹치지 않게 한다
- 한계는 setup에서 미리 로그인하고 환불 요청만 보낸다
- 로그인: 회원 시드 규칙(#431)대로 id 끝자리 1~7은 이메일, 8·9·0은 소셜로 로그인한다. 예전 회원 시드(1~21,000 이메일)가 남은 DB에서는 사용자 시드가 건너뛰므로 DB를 비우고 다시 넣는다

## 실행
모든 명령은 저장소 루트에서 실행한다. 터미널 3개를 쓴다

**터미널 1: 앱** (서버가 켜진 채로 있어서 이 터미널은 앱 전용)
```bash
./gradlew bootRun --args='--spring.profiles.active=local,performance --server.port=8080 --zelo.llm.mode=fake --zelo.social.mode=fake --zelo.portone.mode=fake --zelo.portone.fake-cancel-delay=807ms'
```
- `performance` 프로필이 시드 켜기와 스케줄러 끄기를 함께 적용한다 (#414). 재시작할 때마다 환불 대기 주문이 전부 PAID로 다시 들어간다
- 로그 `부하 테스트 환불 시드 저장 완료 orders=60000`이 나오면 준비 끝

**터미널 2: 서버 지표** (k6 실행 전에 켜고, 끝나면 Ctrl+C)
```bash
load-test/chat/record-metrics.sh docs/load-test/results/refund/dau50k/<단계>-server.csv
```

**터미널 3: 시드 CSV 뽑기 → k6** (한 번에 한 단계만. CSV는 `wc -l`로 60,001줄인지 확인)
```bash
docker compose exec -T postgres psql -U postgres -d zelotest -c "\copy (select o.id as order_id, o.user_id, string_agg(oi.id || ':' || oi.quantity, ';' order by oi.id) as items from orders o join order_items oi on oi.order_id = o.id where o.quote_id like 'LT-REF-%' and o.status = 'PAID' group by o.id, o.user_id, o.quote_id order by o.quote_id) to stdout with csv header" > load-test/refund/data/refund-orders.csv
k6 run --summary-export=docs/load-test/results/refund/dau50k/smoke-summary.json load-test/refund/refund-smoke.js
k6 run -e VUS=2 --summary-export=docs/load-test/results/refund/dau50k/load-summary.json load-test/refund/refund-dau50k-load.js
k6 run -e VUS=2 --summary-export=docs/load-test/results/refund/dau50k/stress-summary.json load-test/refund/refund-dau50k-stress.js
k6 run --summary-export=docs/load-test/results/refund/dau50k/limit-summary.json --out csv=docs/load-test/results/refund/dau50k/limit.csv load-test/refund/refund-dau50k-limit.js
```

**단계마다 재시작:** 스모크·부하·스트레스·한계 **각 단계 전에** 터미널 1의 앱을 다시 실행하고, 터미널 3에서 CSV를 다시 뽑는다. 환불한 주문은 다시 못 쓴다
- CSV 쿼리는 `status = 'PAID'`만 뽑는다. 시드가 다시 들어가지 않았으면 줄 수가 60,001보다 적게 나와서 바로 알 수 있다
- `limit.csv`는 크기가 커서 커밋하지 않는다

## 결과 (로컬, 2026-11-05)
조건: performance 프로필 (커넥션 풀 30, 소셜 로그인 fake 지연 900ms), Tomcat 스레드 200, k6·서버·DB를 PC 한 대에서 실행
- 예전 회원 시드(1~21,000 이메일, 그 뒤 소셜)로 쟀다. 로그인은 측정에서 빼서 환불 결과에는 영향이 없고, 미리 로그인 시간만 달라진다

| 단계 | 초당 환불 | 실패 | 누락 세션 | p95 | Tomcat (최대) | Hikari (최대 / 대기) | 판정 |
|---|---|---|---|---|---|---|---|
| 스모크 (VU 1) | 0.55 | 0% | - | 866ms | 1 | 0 / 0 | 통과 |
| 부하 (목표) | 0.83 | 0% | 0 | 855ms | 2 | 1 / 0 | 통과 |
| 스트레스 (× 10) | 8.3 | 0% | 0 | 848ms | 17 | 1 / 0 | 무너지지 않음 |
| 한계 × 10 | 8 | 0% | 0 | 849ms | 7 | 1 / 0 | 안정 |
| 한계 × 50 | 42 | 0% | 0 | 847ms | 36 | 2 / 0 | 안정 |
| 한계 × 100 | 83 | 1건* | 0 | 847ms | 70 | 4 / 0 | 안정 |
| 한계 × 150 | 125 | 1건* | 0 | 846ms | 105 | 3 / 0 | 안정 |
| 한계 × 200 | 167 | 1건* | 0 | 847ms | 141 | 5 / 0 | 안정 |
| **한계 × 250** | **208** | 1건* | **0** | **847ms** | 176 | 6 / 0 | **안정 (마지막)** |
| 한계 × 300 | 234 (목표 250) | 1건* | 270 | **2.03s** | **200** | 26 / 0 | 기준 초과 (48초에 중단) |

- 안정 판정은 실패율·p95와 함께 **누락 세션(`dropped_iterations`) 0**인지 본다. 누락이 있으면 k6가 다 보내지 못한 것이라 서버 용량으로 읽지 않는다. 누락 수는 `limit-summary.json`의 단계별 값이다
- \* 실패는 스크립트 문제다 (아래 "한계 건수 대조")
- × 300이 48초에 멈춘 이유: **측정 당시 스크립트**는 단계별 p95 2초 초과(10초 뒤부터 판정)에도 `abortOnFail`로 중단했다. **지금 스크립트**는 리뷰를 반영해 p95로는 멈추지 않고 에러율 1% 초과에만 중단한다

| API | 부하 p95 | 스트레스 p95 | 한계 × 250 p95 |
|---|---|---|---|
| 전액 환불 | 856ms | 848ms | 848ms |
| 부분 환불 | 846ms | 841ms | 840ms |

### 건수 대조
기준: **시도** = k6가 보낸 환불 요청, **성공** = 응답 200 + `COMPLETED`, **DB 기록** = `refunds.status = 'COMPLETED'`인 `LT-REF-` 주문의 환불 수

| 단계 | 시도 | 성공 | 실패 | 중단 (응답 전 테스트 종료) | DB 기록 |
|---|---|---|---|---|---|
| 부하 | 350 (예열 100 + 측정 250) | 350 | 0 | 0 | 350 |
| 스트레스 | 2,601 (예열 100 + 측정 2,501) | 2,601 | 0 | 0 | 2,601 |
| 한계 | 49,751 | 49,246 | 5 | 500 | 49,746 |

- 한계: DB 기록 49,746 = 성공 49,246 + 중단 500. 중단된 요청은 k6가 응답을 못 받았지만 서버는 끝까지 처리했다. 시도 49,751 = DB 기록 49,746 + 실패 5
- 실패 5건은 `PAYMENT_008`(결제 완료된 주문만 환불 가능)이다. 단계 경계에서 같은 주문을 두 번 써서 이미 환불한 주문을 다시 요청했다. 환불 기록이 저장되기 전에 거절돼 DB에는 남지 않았다. 지금 스크립트는 단계 사이에 10건 여유를 둔다
- 상태: 전액 환불은 주문·결제·배송 CANCELED, 부분 환불은 주문·결제 PAID + 배송 READY. 음수 재고 0

## 결론
| 항목 | 내용 |
|---|---|
| 목표 | 분당 50세션에서 실패 0%, 누락 0, p95 855ms로 통과. 10배에서도 같다 |
| 안정 한계 | **× 250 = 초당 약 208건** (누락 0, p95 847ms = 포트원 취소 807ms + 서버 약 40ms) |
| 무너지는 지점 | × 300: 초당 250건을 보냈는데 234건만 처리, 누락 270, p95 2.03s |
| 첫 병목 | **Tomcat 스레드 200개.** 포트원 취소를 기다리는 동안 스레드를 잡는다 (× 300: 250 × 0.84초 ≈ 210). 커넥션은 최대 26/30, 대기 0 |
| 다음 | 결제와 같은 구조라 벌크헤드·서킷 브레이커 개선 대상이다 |

---

<details>
<summary><b>예행연습 (DAU 1만, 2026-11-04)</b></summary>

## 목표와 구성
- 목표: 피크 2,000명 × 환불 2% × 1회 ÷ 7,200초 × 1.5 ≈ 0.01 RPS = 100초에 1건
- 시드: 환불 대기 주문 1만 건. 스모크 VU 1, 부하 100초에 1건, 스트레스 10초에 1건, 각 5분
- 조건: 커넥션 풀 10, 예열 없음, 소셜 로그인 지연 없음

## 결과
| 단계 | 환불 요청 | RPS | 실패 | p95 | Tomcat (최대) | 판정 |
|---|---|---|---|---|---|---|
| 스모크 (VU 1) | 71 | 1.17 | 0% | 858ms | 1 | 통과 |
| 부하 (목표) | 4 | 0.013 | 0% | 1.07s | 1 | p95 기준 초과 (첫 요청 1건) |
| 스트레스 (× 10) | 30 | 0.1 | 0% | 867ms | 1 | 무너지지 않음 |

- DB: 환불·주문·결제·배송 상태가 모두 맞았다
- 부하의 p95 초과는 앱을 켠 뒤 첫 요청 1건(예열 전)이다. 표본이 4건이라 p95가 사실상 최댓값이다

## 결론
- 목표와 10배에서 실패 0%. 시간 대부분이 포트원 취소 대기(807ms), 서버 처리는 약 40ms다
- 요청이 겹치지 않아 동시성 병목과 한계는 확인하지 못했다 → DAU 5만 테스트에서 예열과 한계 단계를 추가했다

</details>
