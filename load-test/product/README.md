# 상품 부하 테스트

## 범위
- 상품 목록·검색 `GET /api/products` (검색어 반, 카테고리·재고만 반, 정렬·페이지 랜덤)
- 상품 상세 `GET /api/products/{productCode}`
- 자동완성 `GET /api/products/autocomplete` (검색어 앞 한 글자)
- 인기 상품 `/api/products/popular`, 인기 검색어 `/api/products/popular-keywords`, 카테고리 `/api/categories`
- 로그인 없이 부르는 API라 사용자 범위·토큰을 안 쓴다
- setup의 준비 요청은 측정에서 뺀다 (`kind:product` 태그가 없는 요청)

## 파일
| 파일 | 내용 |
|---|---|
| `product-session.js` | 요청 1번을 비율대로 고르는 흐름 + setup(카테고리·상품 코드 1,000개 준비) |
| `product-smoke.js` | 스모크: VU 1, 1분 |
| `product-load.js` | 부하: 4.2 RPS, 5분 |
| `product-stress.js` | 스트레스: 목표의 1·3·6·10배, 5분 |

## 목표와 VU
- 목표: 4.2 RPS (피크 2,000명 × 상품 호출 10번 ÷ 7,200초 × 1.5)
- 사용자당 10번 = 목록·검색 4, 상세 3, 자동완성 2, 인기 상품·인기 검색어·카테고리 1
- 부하: VU = 4.2 × 스모크 p95(초), 올림(최소 1). preAllocatedVUs = VU × 2 (최소 5), maxVUs = VU × 10
- 스트레스: 4.2 → 12.6 → 25.2 → 42 RPS, 단계마다 10초 올리고 65초 유지

## 준비
1. 상품 10만 개 시드가 들어간 DB (`--zelo.seed.enabled=true --zelo.seed.load-test.enabled=true`로 한 번 띄움)
2. 측정할 때는 시드·스케줄러를 끄고 띄운다 (결제 시드가 남긴 결제 대기 주문을 만료 스케줄러가 처리하면서 같이 돌아서)
   ```bash
   JWT_SECRET=... OPENAI_API_KEY=fake ./gradlew bootRun --args='--spring.profiles.active=performance --zelo.scheduler.enabled=false'
   ```

## 실행
```bash
mkdir -p docs/load-test/results/product
k6 run --summary-export=docs/load-test/results/product/smoke-summary.json load-test/product/product-smoke.js
k6 run -e VUS=1 --summary-export=docs/load-test/results/product/load-summary.json load-test/product/product-load.js
k6 run -e VUS=1 --summary-export=docs/load-test/results/product/stress-summary.json load-test/product/product-stress.js
```
k6마다 다른 탭에서 `load-test/chat/record-metrics.sh docs/load-test/results/product/<단계>-server.csv`를 같이 돌려 서버 지표를 남긴다

## 볼 지표
- k6: `http_req_duration{kind:product}` p95, API별 `http_req_duration{name:...}` p95, `http_req_failed{kind:product}`, `dropped_iterations`
- 서버: Tomcat 바쁜 스레드, Hikari 사용·대기

## 결과 (예행연습, 로컬, 2026-11-04)
조건: performance 프로필, 스케줄러 끔, 상품 10만 개 (판매 중 97,036개)
- 결과 파일: `docs/load-test/results/product/` (검색 수정 후), `docs/load-test/results/product/before/` (수정 전)
- 스모크는 서버 지표를 남기지 않음

### 스모크 (VU 1, 1분): 통과
- 요청 4,245건 (초당 70회), 놓친 요청 0
- checks 100% (4,245/4,245), 실패 0% (0/4,245)
- 응답 시간: avg 14ms, med 6.6ms, p90 39ms, p95 41ms, max 60ms
  - 검색 p95 43ms, 자동완성 18ms, 상세 6.2ms, 인기 상품 3.9ms, 인기 검색어 3.6ms, 카테고리 3.3ms
  - p95는 검색. 검색 개수(count)와 목록 쿼리가 각각 약 20ms
- 부하 VU = 4.2 × 0.041 = 0.17 → 1 (최소 1)

### 부하 (VU 1, 5분): 통과
- 요청 1,261건 (4.2 RPS, 목표 10초에 42번 × 5분 = 1,260), 놓친 요청 0
- 기준 모두 통과: checks 100% (1,261/1,261), 실패 0%, API별 p95 300ms 미만
- 응답 시간: avg 15ms, med 7.6ms, p90 42ms, p95 43ms, max 48ms
  - 검색 p95 45ms, 자동완성 20ms, 상세 7.0ms, 인기 상품 4.0ms, 인기 검색어 3.5ms, 카테고리 3.1ms
  - 스모크와 거의 같다. 목표 트래픽에서는 느려지지 않음
- 서버: Tomcat 바쁜 스레드 최대 1, Hikari 사용 최대 1, 대기 0

### 스트레스 (VU 1 기준, ×1·3·6·10, 5분): 무너지지 않음
- 요청 6,111건 (평균 20.4 RPS, 마지막 단계 42 RPS), 놓친 요청 0
- checks 100% (6,111/6,111), 실패 0%
- 응답 시간: 검색 p95 45ms, 자동완성 19ms, 상세 6.4ms, 인기 상품 3.7ms, 인기 검색어 3.3ms, 카테고리 2.9ms
  - 스모크·부하와 같다. 목표의 10배에서도 느려지지 않음
- 서버: Tomcat 바쁜 스레드 최대 2, Hikari 사용 최대 2, 대기 0
- 한계: 요청 1건이 수십 ms라 42 RPS에서도 동시에 처리한 요청이 최대 2개였다. 커넥션 풀 고갈 같은 동시성 병목은 이번 범위에서 나타나지 않음

### 검색 수정 전과 비교
처음 돌렸을 때 검색·자동완성이 기준을 넘고, 스트레스에서 서버가 멈췄다

| | 수정 전 | 수정 후 |
|---|---|---|
| 스모크 요청 (VU 1, 1분) | 216건 (초당 3.6회) | 4,245건 (초당 70회) |
| 스모크 p95 → 부하 VU | 1.36s → VU 6 | 41ms → VU 1 |
| 부하 검색 p95 | 2.78s (기준 실패) | 45ms |
| 부하 자동완성 p95 | 520ms (기준 실패) | 20ms |
| 스트레스 놓친 요청 | 2,128 / 6,110 | 0 / 6,111 |
| 스트레스 상세 p95 | 19.3s | 6.4ms |
| 스트레스 서버 최대 | Tomcat 200, Hikari 10, 대기 190 | Tomcat 2, Hikari 2, 대기 0 |

- 수정 전 스트레스: ×6(25 RPS)에서 DB 커넥션 10개가 느린 검색에 다 잡혀 190개가 대기, Tomcat 스레드 200개가 꽉 참
  - 원래 빠른 상세(수정 후 6ms)까지 커넥션을 기다리느라 19초로 밀림
- 원인: 같은 쿼리를 5번 넘게 쓰면 PostgreSQL이 검색어를 안 보고 짠 범용 실행 계획을 재사용함
  - 검색 개수(count): 검색어를 보고 짠 계획 20ms, 범용 계획 1,335ms (카테고리 74개마다 trgm 인덱스를 다 훑음)
  - 자동완성: 22ms → 518ms
  - EXPLAIN으로 확인: `prepare` 후 `plan_cache_mode = force_generic_plan`으로 실행
- 수정: 검색·자동완성 트랜잭션 안에서만 `set_config('plan_cache_mode', 'force_custom_plan', true)`
  - 매번 실제 검색어로 계획을 짬. 트랜잭션이 끝나면 원래대로 돌아가 다른 쿼리엔 영향 없음
  - 접속 설정(`prepareThreshold=0`)으로 앱 전체를 바꾸는 방법도 있지만, 다른 도메인 쿼리까지 바뀌어서 상품 쪽만 고침

## 결론
- 상품 조회는 목표(4.2 RPS)와 그 10배(42 RPS)에서 실패 0%, 검색 p95 약 45ms로 버틴다
- 수정 전에는 검색 하나가 1~3초씩 커넥션을 잡아서 25 RPS에서 서버 전체가 멈췄다. 느린 쿼리 하나가 커넥션 풀을 막으면 다른 API까지 같이 느려진다
- 42 RPS에서도 요청이 거의 겹치지 않아 한계 지점은 찾지 못했다. 다음 테스트에서 동시 요청이 생기는 수준까지 올려 본다
  (예: 42 → 100 → 200 → 400 RPS, VU를 넉넉히 주고 Hikari 대기·Tomcat 스레드를 함께 본다)

