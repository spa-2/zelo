# 견적 부하 테스트

## 범위
- 견적 발급 `POST /api/chat/messages` (새 채팅 첫 메시지 = 채팅방 생성 + 메뉴 계획 + **견적 발급** + 견적 설명)
- 견적 수정 `POST /api/chat/rooms/{roomId}/quote-items` (카드의 −/+ 수량 변경, ✕ 빼기)
- 세션 = 로그인 → 견적 발급 → 첫 상품 수량 +1 → 마지막 상품 빼기. 로그인은 `kind` 태그가 없어서 측정에 안 들어간다
- 대체 상품 `quote-substitutes`, 예산 후보 `quote-budget-options`는 fake 메뉴 계획(예산 없음, 품절 없음)으로 상황이 안 생겨서 뺐다

## 파일
| 파일 | 내용 |
|---|---|
| `quote-api.js` | 로그인, 견적 발급, 수량 변경, 빼기 요청. **다른 도메인·전체 시나리오는 이 파일을 가져다 쓴다** |
| `quote-session.js` | 사용자 1명의 세션 (아래 테스트 4개가 같이 씀) |
| `quote-smoke.js` | 스모크: VU 1, 1분 |
| `quote-load.js` | 부하: 분당 125세션, 5분 |
| `quote-stress.js` | 스트레스: 부하 × 10 (분당 1,250세션), 5분 |
| `quote-limit.js` | 한계: 부하 × 1·2·4·6·8·10, 단계마다 60초 |
| `../common/server-monitor.js` | 테스트 중 서버 지표(Tomcat·Hikari·CPU)를 k6가 직접 읽어 summary에 단계별로 남김 |

- `load-test/quote-issue.js`(#244)는 옛 방식이라 이 폴더로 대신한다
  - 돌릴 때마다 새 사용자를 가입시켜 DB에 쌓이고, VU 고정 방식이라 RPS 기준이 아니다
  - 같은 사용자로 계속 보내서 지금은 메시지 제한(1분 10번)에 걸려 429가 난다

### 다른 도메인에서 가져다 쓰기
```js
import { login, issueQuote, changeQuantity, removeItem } from '../quote/quote-api.js';

const token = login(userId);
const issued = issueQuote(token, '김치찌개 재료 준비해 줘');   // { roomId, quote } 또는 실패하면 null
if (issued) {
    const item = issued.quote.items[0];
    const changed = changeQuantity(token, issued.roomId, issued.quote, item, item.quantity + 1);  // 새 견적 카드
}
```
- 요청마다 `kind:quote` 태그가 붙어서 `http_req_duration{kind:quote,name:issueQuote}`로 견적만 볼 수 있다
- 수정할 때마다 견적 ID가 바뀐다. 주문으로 넘길 때는 마지막에 받은 카드의 `quoteId`를 쓴다
- 실패하면 VU마다 처음 3건만 상태 코드·원인을 로그로 남긴다 (`견적 요청 실패 name=... status=...`)

## 목표와 VU
- 목표: DAU 5만 명 중 피크 1시간에 1만 명, 그중 견적을 받는 50%(전체 시나리오 B 30% + C 20%)가 1번
  - 1만 × 50% ÷ 3,600초 × 1.5 = 초당 2.08세션 = **분당 125세션** (견적 요청은 세션당 3개라 6.25 RPS)
- 사용자: 부하 테스트 사용자 시드의 채팅·견적 범위(5,001~15,000) 중 **10,001~15,000**
  - 채팅 테스트와 같이 돌려도 한 사용자가 1분에 10번(메시지 제한)을 넘기지 않게 뒤쪽 절반만 쓴다
  - 세션마다 다음 사용자로 넘어간다
- 스모크: VU 1 고정, 1분 → 세션 p95 측정
- 부하: VU = 초당 세션(2.08) × 스모크 세션 p95(초), 올림 (최소 1), 5분. preAllocatedVUs = VU × 2 (최소 5), maxVUs = VU × 10
- 스트레스: 부하 × 10 (분당 1,250세션, VU × 10), 5분
- 한계: 목표의 1·2·4·6·8·10배, 단계마다 60초. 처음 무너지는 단계와 원인을 찾는다
- 기준: 견적 발급 p95 1초(#244 목표), 수량 변경·빼기 p95 0.3초

## 준비
1. 상품 10만 개, 사용자 3만 명 시드가 들어간 DB (`--zelo.seed.enabled=true --zelo.seed.load-test.enabled=true`로 한 번 띄움)
2. LLM은 fake로, 지연 없이 띄운다. LLM 기다리는 시간을 빼고 서버 쪽 견적 처리만 잰다 (LLM 지연을 넣은 흐름은 채팅 테스트가 본다)
   ```bash
   JWT_SECRET=... OPENAI_API_KEY=fake ./gradlew bootRun --args='--spring.profiles.active=performance --zelo.scheduler.enabled=false --zelo.llm.mode=fake --zelo.llm.fake.plan-intent=SHOPPING_REQUEST'
   ```
   - fake 메뉴 계획은 늘 "김치볶음밥 4인분(김치·밥·계란·대파), 맵지 않게"다

## 실행
```bash
mkdir -p docs/load-test/results/quote
k6 run --summary-export=docs/load-test/results/quote/smoke-summary.json load-test/quote/quote-smoke.js
k6 run -e VUS=1 --summary-export=docs/load-test/results/quote/load-summary.json load-test/quote/quote-load.js
k6 run -e VUS=1 --summary-export=docs/load-test/results/quote/stress-summary.json load-test/quote/quote-stress.js
k6 run --summary-export=docs/load-test/results/quote/limit-summary.json load-test/quote/quote-limit.js
```
- k6를 도커로 돌리면 `-e BASE_URL=http://host.docker.internal:8080`을 넘긴다

## 볼 지표
- k6:
    - `http_req_duration{kind:quote,name:issueQuote}` p95 (견적 발급)
    - `http_req_duration{kind:quote,name:changeQuantity}`, `{...,name:removeItem}` p95 (견적 수정)
    - `http_req_failed{kind:quote}`, `checks{kind:quote}` (201인데 견적 카드가 아니면 checks에서 걸림)
    - `dropped_iterations` (VU가 모자라 못 보낸 세션)
- 서버 (summary의 `server_*{stage:load|stress|x1...}`, 평균·최대):
    - `server_hikari_active` / `server_hikari_pending`: DB 커넥션 사용·대기
    - `server_tomcat_busy`: 바쁜 Tomcat 스레드
    - `server_process_cpu` / `server_system_cpu`: 앱 CPU / PC 전체 CPU

## 결과 (로컬, 2026-11-05, 새 기준 DAU 5만)
조건: performance 프로필, 스케줄러 끔, LLM fake(지연 없음), 상품 10만 개·사용자 3만 명. k6·서버·DB를 PC 한 대(도커)에서 같이 돌렸다
- 결과 파일: `docs/load-test/results/quote/` (견적 후보 수정 후), `before/` (수정 전). `*-summary.json`에 서버 지표 `server_*{stage:...}`도 들어 있음

### 요약 (견적 발급 p95 기준)
| 단계 | 조건 | 수정 전 | 수정 후 | 판정 (수정 후) |
|---|---|---|---|---|
| 스모크 | VU 1, 1분, 쉬지 않고 | 세션 76개, 발급 779ms, 세션 862ms | 세션 159개, 발급 **328ms**, 세션 414ms | 통과 |
| 부하 | 분당 125세션 (목표), 5분 | 발급 995ms (기준 1초 겨우 통과), VU 2 | 발급 **325ms**, VU 1 | 통과 |
| 스트레스 | 분당 1,250세션 (목표 × 10), 5분 | 보낼 세션 6,250개 중 **4,152개(66%) 못 보냄**, 발급 2.47s | 6,244개 보냄(못 보냄 2), 발급 **340ms** | 무너지지 않음 |

- 실패(`http_req_failed`)·checks 실패는 수정 전·후 모두 0건
- 부하 VU = 초당 2.08세션 × 스모크 세션 p95 → 수정 전 0.86초 → 2, 수정 후 0.41초 → 1
- 견적 수정(수량 변경·빼기) p95는 평소 10~17ms. 수정 전 스트레스에서는 커넥션을 기다리느라 1.10s / 0.88s로 밀렸다

### 서버 (평균 / 최대)
| 단계 | Tomcat 바쁜 스레드 | Hikari 사용 | Hikari 대기 | 앱 CPU | PC 전체 CPU |
|---|---|---|---|---|---|
| 부하 수정 전 | 2.0 / 3 | 1.9 / 3 | 0 / 0 | 14% / 36% | 26% / 100% |
| 부하 수정 후 | 0.7 / 1 | 0.7 / 1 | 0 / 0 | 2% / 11% | 9% / 25% |
| 스트레스 수정 전 | 18.8 / 20 | **9.9 / 10** | **8.6 / 10** | 58% / 63% | 83% / 91% |
| 스트레스 수정 후 | 8.2 / 9 | 7.9 / 9 | 0 / 0 | 16% / 18% | 39% / 52% |

### 한계
목표(분당 125세션 = 초당 2.08세션)의 배수로 단계마다 60초씩 올렸다. 수정 후는 ×10에서도 안 무너져서 ×12~×30을 한 번 더 돌렸다 (`limit-extra-summary.json`)

| 단계 | 초당 세션(목표) | 수정 전: 발급/초, 못 보냄, 발급 p95, Hikari 대기(평균/최대) | 수정 후: 발급/초, 못 보냄, 발급 p95, Hikari 대기(평균/최대) |
|---|---|---|---|
| ×1 | 2.1 | 2.1, 0, 882ms, 0 / 0 | 2.1, 0, 328ms, 0 / 0 |
| ×2 | 4.2 | 4.2, 0, 1.03s, 0 / 0 | 4.2, 0, 333ms, 0 / 0 |
| ×4 | 8.3 | **7.4, 58, 3.74s, 27 / 40** | 8.4, 0, 337ms, 0 / 0 |
| ×6 | 12.5 | 7.5, 299, 6.09s, 62 / 65 | 12.5, 0, 340ms, 0 / 0 |
| ×8 | 16.7 | 6.8, 596, 9.59s, 94 / 141 | 16.7, 0, 340ms, 0 / 0 |
| ×10 | 20.8 | 8.1, 768, 11.45s, 107 / 137 | 20.7, 0, 341ms, 0 / 0 |
| ×12 | 25.0 | - | 25.0, 0, 474ms, 1.6 / 13 |
| ×16 | 33.3 | - | **26.5, 411, 2.94s, 154 / 190** |
| ×20 | 41.7 | - | 26.3, 923, 3.89s, 190 / 190 |
| ×30 | 62.5 | - | 25.1, 2,217, 6.87s, 190 / 190 |

- **수정 전**: 목표의 2배(초당 4.2건)까지 버팀. **4배에서 무너짐**: DB 커넥션 10개가 다 차고 최대 40개가 기다림. 최대 처리량 초당 약 7.5건
  - 원래 10ms인 수량 변경도 커넥션을 기다리느라 2.2~9.7초로 밀렸다
- **수정 후**: 목표의 12배(초당 25건)까지 버팀(p95 474ms). **16배에서 무너짐**: 커넥션 10개가 다 차고 Tomcat 스레드 200개 중 190개가 기다림. 최대 처리량 초당 약 26건 (**수정 전의 3.5배**)
- 두 경우 모두 실패(500)는 0건이고, 처리하지 못한 만큼 k6가 세션을 못 보냈다(VU 부족)

### 원인과 수정 (견적 후보)
- **원인**: 견적을 만들 때 재료에 해당하는 판매 중 상품을 **전부** 불러와 점수를 매겼다
  - 김치볶음밥(김치·밥·계란·대파) 1번에 **17,869개** (김치만 7,737개). 그 상품들의 재고·키워드·리뷰를 다시 17,869개씩 조회
  - 상품 825개일 때(#244) 0.2초였던 발급이 10만 개에서 0.7~0.8초가 됐고, 그동안 DB 커넥션을 계속 잡았다
  - 상품 ID를 한 쿼리에 넣는 개수는 최대 32,767개라, 인기 재료가 많은 메뉴(김치찌개+밥+계란+쌀 ≈ 31,648개)는 쿼리 자체가 실패할 수 있었다
- **수정**: DB에서 후보만 먼저 고른다 (`ProductRepository.findQuoteCandidateIds`)
  - 재료마다 (카테고리, 맞춘 조건 키워드 묶음)별로 판매량 상위 100개 + 필요한 양을 샀을 때 총액 하위 100개
  - 견적 점수는 판매량 40 + 리뷰 30 + 총액 30이라, 이길 상품은 판매량이나 총액 쪽 상위에 있다
  - 김치볶음밥 기준 17,869개 → 989개, 후보 쿼리는 DB에서 약 50ms
- **결과가 안 바뀌는지 확인**: 로컬 10만 개 DB에서 재료 126개 × 양 3가지 × 조건 11가지 = **4,136가지를 예전 방식과 비교 → 고른 상품·대체 상품·예산 후보·빠진 재료가 모두 같았다**
  - 후보를 30개로 하면 5가지(0.12%)가 달라서 100개로 정했다
- **남은 시간**: 수정 후 발급 0.33초 중 후보 쿼리 약 50ms 외에, 후보 약 1,000개의 재고·키워드·리뷰 조회가 각각 약 40ms 걸린다 (ID 1,000개를 넘기는 비용). 재고·리뷰는 다른 도메인이라 이번에는 손대지 않았다

## 결론
| 항목 | 내용 |
|---|---|
| 버티는가 | 새 목표(초당 2.08세션)와 그 10배에서 실패 0%, 발급 p95 약 0.33초 |
| 한계 | 수정 전 초당 약 7.5건(목표의 약 3.5배) → 수정 후 초당 약 26건(목표의 약 12배). 둘 다 DB 커넥션 10개가 먼저 찬다 |
| 수정 전 문제 | 견적 하나가 상품 1만 7천여 개를 읽으며 커넥션을 0.7초 넘게 잡아서, 목표의 4배에서 커넥션이 막히고 수량 변경 같은 가벼운 요청까지 같이 느려졌다 |
| 다른 도메인 영향 | 채팅 테스트에서 커넥션을 오래 잡던 것이 견적 발급이었다. 새 기준에서 채팅 목표는 메시지 초당 21건이라, 견적이 빨라져도 채팅 쪽 한계는 따로 다시 봐야 한다 |
| 다음 | ① 재고·리뷰 조회를 후보 쿼리와 묶거나 배열 한 개로 넘겨 ID 1,000개 비용 줄이기 (재고·리뷰 담당과 상의) ② 대체 상품·예산 버튼은 품절·예산 초과 상황을 만드는 fake 계획이 생기면 추가 ③ AWS에서 커넥션 풀 크기별로 다시 측정 |
