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
| `product-stress.js` | 스트레스: 부하 × 10 (42 RPS), 5분 |

## 목표와 VU
- 목표: 4.2 RPS = 10초에 42번 (피크 2,000명 × 상품 호출 10번 ÷ 7,200초 × 1.5)
- 사용자당 10번 = 목록·검색 4, 상세 3, 자동완성 2, 인기 상품·인기 검색어·카테고리 1
- 스모크: VU 1 고정, 1분 → p95 측정
- 부하: VU = 4.2 × 스모크 p95(초), 올림 (최소 1), 5분. preAllocatedVUs = VU × 2 (최소 5), maxVUs = VU × 10
- 스트레스: 부하 × 10 (10초에 420번, VU × 10), 5분
- 바깥 API를 부르지 않아서 지연(fake-delay)은 넣지 않는다. 측정값이 그대로 서버 처리 시간

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
테스트 중에는 서버 지표를 같이 기록한다: `load-test/chat/record-metrics.sh docs/load-test/results/product/<단계>-server.csv`

## 볼 지표
- k6:
    - `http_req_duration{kind:product}` p95, p99
    - API별 `{name:search}`, `{name:detail}`, `{name:autocomplete}`, `{name:popular}`, `{name:popularKeywords}`, `{name:categories}`
    - `http_req_failed{kind:product}`
    - `checks`
    - `dropped_iterations`
- 서버:
    - Hikari `hikaricp_connections_active` / `pending`
    - Tomcat `tomcat_threads_busy_threads`

## 결과 (예행연습, 로컬, 2026-11-04)
조건: performance 프로필, 스케줄러 끔, 상품 10만 개 (판매 중 97,036개). p95 기준(300ms)은 임시값
- 결과 파일: `docs/load-test/results/product/` (검색 수정 후), `docs/load-test/results/product/before/` (수정 전)

### 요약 (검색 수정 후)
| 단계 | 조건 | 요청 | RPS | 실패 | checks | p95 | 판정 |
|---|---|---|---|---|---|---|---|
| 스모크 | VU 1, 1분, 쉬지 않고 | 4,245 | 70 | 0% | 100% (4,245/4,245) | 41ms | 통과 |
| 부하 | 10초에 42번 (목표 4.2 RPS), VU 1, 5분 | 1,261 | 4.2 | 0% | 100% (1,261/1,261) | 43ms | 통과 |
| 스트레스 | 10초에 420번 (목표 × 10), VU 10, 5분 | 12,601 | 42 | 0% | 100% (12,601/12,601) | 41ms | 무너지지 않음 |

### 상품 요청 응답 시간 (`kind:product`)
| 단계 | min | avg | med | p90 | p95 | max |
|---|---|---|---|---|---|---|
| 스모크 | 1.7ms | 14ms | 6.6ms | 39ms | 41ms | 60ms |
| 부하 | 2.4ms | 15ms | 7.6ms | 42ms | 43ms | 48ms |
| 스트레스 | 1.1ms | 14ms | 6.6ms | 39ms | 41ms | 97ms |

### API별 p95
| 단계 | 검색 | 자동완성 | 상세 | 인기 상품 | 인기 검색어 | 카테고리 |
|---|---|---|---|---|---|---|
| 스모크 | 43ms | 18ms | 6.2ms | 3.9ms | 3.6ms | 3.3ms |
| 부하 | 45ms | 20ms | 7.0ms | 4.0ms | 3.5ms | 3.1ms |
| 스트레스 | 43ms | 19ms | 5.7ms | 3.5ms | 3.1ms | 2.7ms |

- 가장 느린 건 검색. 검색 개수(count)와 목록 쿼리가 각각 약 20ms
- 세 단계가 거의 같다. 목표의 10배에서도 느려지지 않음
- 스트레스를 처음 돌렸을 때 응답 0ms·상태 0(연결 실패)인 요청 2건(0.01%)이 있었고, 다시 돌린 결과를 적었다. 서버 오류는 아니고 로컬 Docker 네트워크로 보임

### 서버
| 단계 | Tomcat 바쁜 스레드 (최대) | Hikari 사용 (최대) | Hikari 대기 | 놓친 요청 |
|---|---|---|---|---|
| 스모크 | - (기록 안 함) | - | - | 0 |
| 부하 | 1 | 1 | 0 | 0 |
| 스트레스 | 2 | 2 | 0 | 0 |

- 부하 VU = 4.2 × 0.041 = 0.17 → 1 (최소 1)

### 검색 수정 전과 비교
처음 돌렸을 때 검색·자동완성이 기준을 넘고, 스트레스에서 서버가 멈췄다

| 단계 | 항목 | 수정 전 | 수정 후 |
|---|---|---|---|
| 스모크 | 요청 (VU 1, 1분) | 216 (3.6 RPS) | 4,245 (70 RPS) |
| 스모크 | p95 → 부하 VU | 1.36s → VU 6 | 41ms → VU 1 |
| 부하 | p95 (전체 / 검색 / 자동완성) | 2.70s / 2.78s / 520ms | 43ms / 45ms / 20ms |
| 부하 | 판정 | 실패 0%지만 p95 기준 초과 | 통과 |
| 스트레스 | 보낸 요청 / 놓친 요청 | 5,885 / 6,715 (53%) | 12,601 / 0 |
| 스트레스 | p95 (전체 / 검색 / 상세) | 6.66s / 7.72s / 4.71s | 41ms / 43ms / 5.7ms |
| 스트레스 | 서버 최대 | Tomcat 60, Hikari 10, 대기 50 | Tomcat 2, Hikari 2, 대기 0 |

- 수정 전 스트레스(VU 60): DB 커넥션 10개가 느린 검색에 다 잡혀 50개가 대기. VU 60개가 모두 응답을 기다리느라 보낼 요청의 절반 이상을 못 보냄
  - 원래 빠른 상세(수정 후 6ms)·인기 상품(4ms)까지 커넥션을 기다리느라 4초대로 밀림
  - 커넥션을 안 쓰는 인기 검색어(Redis)·카테고리(캐시)는 3~4ms 그대로
- 원인: 같은 쿼리를 여러 번 쓰면 PostgreSQL이 검색어를 안 보고 짠 범용 실행 계획을 재사용함
  - JDBC가 같은 쿼리를 5번 쓰면 서버 쪽 준비된 쿼리로 바꾸고, PostgreSQL이 그 뒤 몇 번 비교해 범용 계획으로 넘어감. 로컬에서는 같은 검색 10번째부터 50ms → 2.6초
  - 검색 개수(count): 검색어를 보고 짠 계획 20ms, 범용 계획 1,335ms (카테고리 74개마다 trgm 인덱스를 다 훑음)
  - 자동완성: 22ms → 518ms
  - EXPLAIN으로 확인: `prepare` 후 `plan_cache_mode = force_generic_plan`으로 실행
- 수정: 검색·자동완성 트랜잭션 안에서만 `set_config('plan_cache_mode', 'force_custom_plan', true)`
  - 매번 실제 검색어로 계획을 짬. 트랜잭션이 끝나면 원래대로 돌아가 다른 쿼리엔 영향 없음
  - 접속 설정(`prepareThreshold=0`)으로 앱 전체를 바꾸는 방법도 있지만, 다른 도메인 쿼리까지 바뀌어서 상품 쪽만 고침

## 결론
| 항목 | 내용 |
|---|---|
| 버티는가 | 목표(4.2 RPS)와 그 10배(42 RPS)에서 실패 0%, 전체 p95 약 40ms, 검색 p95 약 45ms |
| 시간 구성 | 바깥 API 없이 서버·DB 처리만. 검색이 가장 느리고 count와 목록 쿼리가 각각 약 20ms |
| 수정 전 | 검색 하나가 1~3초씩 커넥션을 잡아서 42 RPS에서 요청 절반을 못 보냄. 느린 쿼리 하나가 커넥션 풀을 막으면 다른 API까지 같이 느려진다 |
| 한계 | 42 RPS에서도 동시에 처리한 요청이 최대 2개. 커넥션 풀 고갈 같은 동시성 병목과 한계 RPS는 확인하지 못함 |
| 다음 | 동시 요청이 생기는 수준(예: 42 → 100 → 200 → 400 RPS)까지 올리고 Hikari 대기·Tomcat 스레드를 함께 본다 |
