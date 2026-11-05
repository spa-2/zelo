# 상품 부하 테스트

## 범위
- 상품 목록·검색 `GET /api/products` (검색어 반, 카테고리·재고만 반, 정렬·페이지 랜덤)
- 상품 상세 `GET /api/products/{productCode}` (30%는 판매량 상위 100개, 70%는 LT- 상품 중 아무거나)
- 자동완성 `GET /api/products/autocomplete` (검색어 앞 한 글자)
- 인기 상품 `/api/products/popular`, 인기 검색어 `/api/products/popular-keywords`, 카테고리 `/api/categories`
- 로그인 없이 부르는 API라 사용자 범위·토큰을 안 쓴다
- setup의 준비 요청은 측정에서 뺀다 (`kind:product` 태그가 없는 요청)

## 파일
| 파일 | 내용 |
|---|---|
| `product-api.js` | 상품 API 요청 하나하나 + setup 데이터 준비 + 전체 시나리오 A(둘러보기, 요청 9개) 흐름. **다른 도메인·전체 시나리오는 이 파일을 가져다 쓴다** |
| `product-session.js` | 요청 1번을 비율대로 고르는 흐름 (아래 테스트 4개가 같이 씀) |
| `product-smoke.js` | 스모크: VU 1, 1분 |
| `product-load.js` | 부하: 41.7 RPS, 5분 |
| `product-stress.js` | 스트레스: 부하 × 10 (417 RPS), 5분 |
| `product-limit.js` | 한계: 부하 × 1·3·5·10·15·20·30 (42~1,251 RPS), 단계마다 90초 |
| `../common/server-monitor.js` | 테스트 중 서버 지표(Tomcat·Hikari·CPU)를 k6가 직접 읽어 summary에 단계별로 남김 |

### 다른 도메인에서 가져다 쓰기
```js
import { loadProductData, browseFlow, search, getDetail } from '../product/product-api.js';

export function setup() {
    return { product: loadProductData() };   // 카테고리 ID, 판매량 상위 100개 코드
}
export function userFlow(data) {
    browseFlow(data.product);                // 카테고리 → 인기 상품 → 인기 검색어 → 자동완성 2번 → 검색 → 검색 2쪽 → 상세 2번
}
```
- 요청마다 `kind:product` 태그가 붙는다. 다른 도메인 요청과 섞여도 `http_req_duration{kind:product}`로 상품만 볼 수 있다
- 기준(threshold)은 `{kind:product,name:search}`처럼 `kind`를 같이 걸어야 다른 도메인의 같은 이름 요청과 안 섞인다
- 실패하면 VU마다 처음 3건만 상태 코드·원인을 로그로 남긴다 (`상품 요청 실패 name=... status=...`)
- 요청 시간 제한은 10초 (`-e REQUEST_TIMEOUT=`으로 바꿈)

## 목표와 VU
- 목표: **41.7 RPS** = 10초에 417번 (DAU 5만 명 중 피크 1시간에 1만 명 × 상품 호출 10번 ÷ 3,600초 × 1.5)
  - 예전 기준(DAU 1만, 피크 2시간 2,000명)은 4.2 RPS였다. 새 기준의 목표가 예전 스트레스(×10)와 같다
- 사용자당 10번 = 목록·검색 4, 상세 3, 자동완성 2, 인기 상품·인기 검색어·카테고리 1
- 스모크: VU 1 고정, 1분 → p95 측정
- 부하: VU = 41.7 × 스모크 p95(초), 올림 (최소 1), 5분. preAllocatedVUs = VU × 2 (최소 5), maxVUs = VU × 10
- 스트레스: 부하 × 10 (10초에 4,170번 = 417 RPS, VU × 10), 5분
- 한계: 목표의 1·3·5·10·15·20·30배(42~1,251 RPS) 7단계로 올려 처음 무너지는 단계를 찾는다. 단계마다 90초, 단계를 시나리오로 나눠 summary에 단계별 값이 남는다
  - VU는 단계 RPS만큼 미리 만들고(응답 1초까지) 그 2배까지 늘린다
- 바깥 API를 부르지 않아서 지연(fake-delay)은 넣지 않는다. 측정값이 그대로 서버 처리 시간

## 준비
1. 상품 10만 개 시드가 들어간 DB (`--zelo.seed.enabled=true --zelo.seed.load-test.enabled=true`로 한 번 띄움)
2. 측정할 때는 시드·스케줄러를 끄고 띄운다 (결제 시드가 남긴 결제 대기 주문을 만료 스케줄러가 처리하면서 같이 돌아서)
   ```bash
   JWT_SECRET=... OPENAI_API_KEY=fake ./gradlew bootRun --args='--spring.profiles.active=performance --zelo.scheduler.enabled=false'
   ```
3. 상품 상세 캐시(Redis, 1시간)를 비우고 시작한다. 앞 테스트에서 캐시에 들어간 상품이 남아 있으면 상세가 실제보다 빠르게 나온다
   ```bash
   docker exec zelotest-redis sh -c "redis-cli --scan --pattern 'productDetail*' | xargs -r redis-cli del"
   ```

## 실행
```bash
mkdir -p docs/load-test/results/product
k6 run --summary-export=docs/load-test/results/product/smoke-summary.json load-test/product/product-smoke.js
k6 run -e VUS=1 --summary-export=docs/load-test/results/product/load-summary.json load-test/product/product-load.js
k6 run -e VUS=1 --summary-export=docs/load-test/results/product/stress-summary.json load-test/product/product-stress.js
k6 run --summary-export=docs/load-test/results/product/limit-summary.json load-test/product/product-limit.js
```
- k6를 도커로 돌리면 `-e BASE_URL=http://host.docker.internal:8080`을 넘긴다. 서버 지표 주소(9292)도 같은 호스트로 맞춰진다
- 서버 지표는 k6가 2초마다 직접 읽어 summary의 `server_*{stage:...}`에 남긴다. 따로 기록 스크립트를 켜고 끌 필요가 없다

## 볼 지표
- k6:
    - `http_req_duration{kind:product}` p95, p99
    - API별 `{kind:product,name:search}`, `detail`, `autocomplete`, `popular`, `popularKeywords`, `categories`
    - `http_req_failed{kind:product}`, `checks{kind:product}`
    - `dropped_iterations` (VU가 모자라 못 보낸 요청)
- 서버 (summary의 `server_*{stage:load|stress|x10...}`, 평균·최대):
    - `server_hikari_active` / `server_hikari_pending`: DB 커넥션 사용·대기
    - `server_tomcat_busy`: 바쁜 Tomcat 스레드
    - `server_process_cpu` / `server_system_cpu`: 앱 CPU / PC 전체 CPU (k6·DB가 같은 PC면 전체 CPU가 먼저 찬다)

## 결과 (로컬, 2026-11-05, 새 기준 DAU 5만)
조건: performance 프로필, 스케줄러 끔, 상품 10만 개 (판매 중 97,036개), 시작 전에 상품 상세 캐시 비움. p95 기준(300ms)은 임시값
- 결과 파일: `docs/load-test/results/product/` (`*-summary.json`에 서버 지표 `server_*{stage:...}`도 들어 있음), `before/` (#399 검색 수정 전, 예전 기준)
- k6·서버·DB를 PC 한 대(도커)에서 같이 돌렸다

### 요약
| 단계 | 조건 | 상품 요청 | RPS | 실패 | checks | p95 | 판정 |
|---|---|---|---|---|---|---|---|
| 스모크 | VU 1, 1분, 쉬지 않고 | 4,613 | 77 | 0% | 100% | 38ms | 통과 |
| 부하 | 10초에 417번 (목표 41.7 RPS), VU 2, 5분 | 12,511 | 41.7 | 0.02% (2건) | 99.98% | 39ms | 통과 |
| 스트레스 | 10초에 4,170번 (목표 × 10 = 417 RPS), VU 20, 5분 | 125,086 | 417 | 0.01% (12건) | 99.99% | 48ms | 무너지지 않음 (못 보낸 요청 14) |

- 부하 VU = 41.7 × 0.038 = 1.6 → 2
- 실패 14건은 모두 k6 쪽 `request timeout`(status 0)이고 서버 로그에는 오류·경고가 없다. 로컬 도커 네트워크로 보인다

### 상품 요청 응답 시간 (`kind:product`)
| 단계 | min | avg | med | p90 | p95 | max |
|---|---|---|---|---|---|---|
| 스모크 | 1.6ms | 13ms | 6.2ms | 37ms | 38ms | 50ms |
| 부하 | 0ms | 13ms | 6.4ms | 38ms | 39ms | 51ms |
| 스트레스 | 0ms | 15ms | 6.4ms | 44ms | 48ms | 94ms |

### API별 p95
| 단계 | 검색 | 자동완성 | 상세 | 인기 상품 | 인기 검색어 | 카테고리 |
|---|---|---|---|---|---|---|
| 스모크 | 40ms | 17ms | 5.9ms | 3.7ms | 2.9ms | 2.5ms |
| 부하 | 41ms | 18ms | 5.8ms | 3.7ms | 2.9ms | 2.5ms |
| 스트레스 | 53ms | 22ms | 5.8ms | 3.7ms | 2.9ms | 2.5ms |

- 가장 느린 건 검색. 검색 개수(count)와 목록 쿼리가 각각 약 20ms
- 상세는 이번부터 70%를 캐시에 없는 상품으로 불러서, 예전처럼 캐시만 맞는 값이 아니다

### 서버 (평균 / 최대)
| 단계 | Tomcat 바쁜 스레드 | Hikari 사용 | Hikari 대기 | 앱 CPU | PC 전체 CPU |
|---|---|---|---|---|---|
| 부하 | 0.6 / 2 | 0.6 / 2 | 0 / 0 | 1% / 1% | 10% / 16% |
| 스트레스 | 5.9 / 16 | 5.7 / **10** | 0.1 / 7 | 5% / 6% | 51% / 62% |

- 스트레스(417 RPS)에서 DB 커넥션 10개가 가끔 다 차고 최대 7개가 잠깐 기다렸다. 버티지만 여유가 거의 없다

### 검색 수정 전과 비교 (#399, 예전 기준 4.2 RPS로 잰 값)
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

### 한계 RPS
목표의 1배부터 30배까지 단계마다 90초씩 올렸다. 결과 파일: `limit-summary.json` (단계별 = `stage` 태그)

| 단계 | 목표 RPS | 처리한 RPS | 못 보낸 요청 | 실패 | 전체 p95 | 상세 p95 | Tomcat (평균/최대) | Hikari 사용 (평균/최대) | Hikari 대기 (평균/최대) | PC 전체 CPU (평균/최대) |
|---|---|---|---|---|---|---|---|---|---|---|
| ×1 | 42 | 42 | 0 | 0% | 39ms | 5.7ms | 0.6 / 2 | 0.5 / 2 | 0 / 0 | 10% / 44% |
| ×3 | 125 | 125 | 0 | 0% | 39ms | 5.5ms | 1.6 / 4 | 1.5 / 4 | 0 / 0 | 18% / 24% |
| ×5 | 208 | 209 | 0 | 0% | 40ms | 5.3ms | 2.8 / 5 | 2.7 / 5 | 0 / 0 | 26% / 36% |
| ×10 | 417 | 417 | 0 | 0% | 49ms | 5.8ms | 6.0 / 11 | 6.0 / **10** | 0 / 1 | 52% / 59% |
| ×15 | 625 | **588** | 3,395 | 0% | **2.28s** | 2.27s | **195 / 200** | 9.9 / 10 | **184 / 190** | 81% / 85% |
| ×20 | 834 | 583 | 22,619 | 0% | 3.05s | 3.03s | 200 / 200 | 10 / 10 | 190 / 190 | 82% / 87% |
| ×30 | 1,251 | 594 | 59,090 | 0.71% | 4.43s | 4.41s | 200 / 200 | 10 / 10 | 190 / 190 | 83% / 87% |

- **버티는 한계: 417 RPS (새 목표의 10배)**. 실패 0%, p95 49ms. 이 단계부터 DB 커넥션 10개가 가끔 다 찬다
- **무너지는 지점: 625 RPS (×15)**
  - 커넥션 10개가 계속 다 차고 Tomcat 스레드 200개 중 190개가 커넥션을 기다림
  - 응답이 0.04초 → 2.3초, 처리량은 약 590 RPS에서 멈추고 나머지는 VU가 모자라 못 보냄
  - 검색뿐 아니라 상세(원래 6ms)도 2.3초로 같이 밀림. 느린 API가 아니라 커넥션 풀 크기 자체가 병목
- **최대 처리량: 약 590 RPS**. 더 보내도 처리량은 늘지 않고 응답만 길어짐
- ×30의 실패는 전부 k6의 10초 시간 초과(`request timeout`)다. 서버 로그에는 오류가 없다 (실패 로그로 확인)
- 앱 CPU는 10% 안팎인데 PC 전체 CPU가 85%까지 찼다. 같은 PC의 k6(최대 VU 2,500)·DB가 CPU를 쓴 것이라, 서버를 따로 둔 환경에서는 한계가 달라질 수 있다

## 결론
| 항목 | 내용 |
|---|---|
| 버티는가 | 새 목표(41.7 RPS)와 그 10배(417 RPS)에서 실패 0.02% 이하(전부 k6 쪽 시간 초과), 전체 p95 39~48ms, 검색 p95 41~53ms |
| 시간 구성 | 바깥 API 없이 서버·DB 처리만. 검색이 가장 느리고 count와 목록 쿼리가 각각 약 20ms |
| 한계 | 417 RPS(새 목표의 10배)까지 버팀. 625 RPS에서 DB 커넥션 10개가 다 차서 응답이 2.3초로 늘고, 최대 처리량은 약 590 RPS |
| 여유 | 스트레스(417 RPS)가 한계 바로 아래라 여유가 거의 없다. 채팅·주문 등 다른 도메인과 커넥션 10개를 같이 쓰면 더 빨리 찬다 |
| 다음 | 커넥션 풀을 늘리면(예: 10 → 20) 한계가 올라가는지, 아니면 DB CPU가 먼저 차는지 AWS에서 확인. 커넥션을 가장 오래 잡는 검색(count + 목록 약 40ms)을 줄이는 방법도 같이 본다 |
