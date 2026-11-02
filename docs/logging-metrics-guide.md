# 로그·지표 작성 가이드

모니터링(Prometheus, Grafana, Loki)에서 쓸모 있는 데이터를 남기기 위한 팀 기준입니다.
코드를 짜다가 "이걸 지표로 남길까, 로그로 남길까, 어떤 레벨로 찍을까"가 헷갈릴 때 이 문서를 봅니다.

## 0. 먼저 보는 요약: 내가 무엇을 언제 하면 되는가

`GlobalExceptionHandler`가 **실패 로그와 실패 횟수를 이미 남깁니다.** 그래서 팀원은 실패 쪽 작업이 거의 없고, **성공 지표와 성공 이정표 로그**만 신경 쓰면 됩니다.

| 언제 | 무엇을 | 어떻게 |
|---|---|---|
| 실패 처리를 짤 때 (거부, 검증, 권한 등) | 아무것도 안 한다 | `throw new BusinessException(...)`만 한다. 로그와 `zelo.api.error` 집계는 핸들러가 한다 |
| 새 `ErrorCode`를 추가할 때 | 아무것도 안 한다 | 코드 값이 자동으로 집계 태그가 된다 |
| 기능이 **성공**했을 때 (가입, 주문 생성, 결제 승인 등) | 횟수 지표 + 이정표 INFO 로그 | `businessMetrics.count(...)` 한 줄 + `log.info("... id={}", id)` 한 줄 |
| 외부 호출이나 오래 걸리는 작업을 할 때 | 시간 지표 | `businessMetrics.record(...)` 또는 `time(...)` |
| 예외를 **잡고 삼킬 때** (스케줄러, 메일, 보조 작업) | 로그를 찍는다 | `log.warn/error("...", e)`. 위로 안 올라가서 핸들러가 모른다 |
| 예외를 `BusinessException`으로 **바꿔 던질 때** | 바꾸기 전에 원래 예외를 로그로 남긴다 | `catch`에서 `log.warn("...", e)` 후 `throw new BusinessException(...)` |
| 실패했지만 예외 없이 **대체 처리**했을 때 (FALLBACK 등) | 횟수 지표 + WARN 로그 | 핸들러에 안 닿으므로 직접 센다 |
| 컨트롤러 단위 테스트를 쓸 때 | 핸들러 생성 방식을 맞춘다 | `new GlobalExceptionHandler(new BusinessMetrics(new SimpleMeterRegistry()))` |
| PR을 올리기 전 | 아래 [6. 체크리스트](#6-작성-전-체크리스트)를 확인한다 | |

새 지표 이름은 `BusinessMetric` enum에만 추가합니다. 파트마다 이름이 다르면 대시보드에서 묶을 수 없습니다.

## 1. 지표와 로그, 무엇이 다른가

| | 지표 (`BusinessMetrics`) | 로그 (`log.xxx`) |
|---|---|---|
| 답하는 질문 | **얼마나** 자주, 얼마나 오래? | **왜**, 이 요청 한 건에서 무슨 일이? |
| 저장 위치 | Prometheus → Grafana 그래프 | Loki → Grafana 로그 검색 |
| 단위 | 집계된 숫자 | 요청 1건 |
| 찾는 방법 | 그래프 | `traceId`, `userId`, `roomId` 검색 |

로그에는 `traceId`, `spanId`, `userId`, `roomId`가 자동으로 붙습니다. 로그를 찍을 때 따로 넣지 않습니다.
단, 로그인과 가입처럼 **인증 전 요청은 `user=`가 비어 나옵니다.** 이 경우 성공 로그의 본문에 `userId`를 직접 적습니다.

## 2. 지표로 남길지 로그로 남길지

```
"몇 번 일어났는지, 얼마나 걸렸는지"가 궁금하다  →  지표
"이 한 건이 왜 그랬는지"가 궁금하다            →  로그
둘 다 궁금하다 (예상한 실패)                    →  지표 + WARN 로그 한 줄
```

- **로그 줄 수로 횟수를 세지 않는다.** 횟수는 지표가 더 정확하고 가볍다.
- 성공할 때마다 INFO를 찍어서 세는 방식은 쓰지 않는다. 로그가 폭증한다.
- **지표 태그에는 종류가 몇 개로 정해진 값만** 넣는다 (`SUCCESS`, `AI_TIMEOUT` 등). `userId`, `orderId`, `roomId`는 넣지 않는다. 값 조합마다 시계열이 생겨 Prometheus가 감당하지 못한다.
- 카운터는 앱을 재시작하면 0으로 돌아간다. Grafana에서는 `increase()`로 구간 증가량을 본다. 전체 회원 수 같은 절대값은 DB에서 센다.

| 상황 | 지표 | 로그 |
|---|---|---|
| 성공 | 센다 | 보통 안 찍음. 비즈니스 이정표만 INFO |
| 예상한 실패 (예외로 던져 핸들러까지 가는 경우) | **핸들러가 센다** (직접 세지 않는다) | **핸들러가 찍는다** |
| 예상한 실패 (예외 없이 대체 처리, 삼킨 예외) | 직접 센다 | WARN 한 줄 (원인) |
| 예상 못 한 실패 | 핸들러가 센다 (`INTERNAL_ERROR`) | ERROR + 스택트레이스 (핸들러) |

## 3. 로그 레벨 기준

기준은 **"이 로그를 본 사람이 무엇을 해야 하는가"** 입니다.

| 레벨 | 의미 | 예시 |
|---|---|---|
| **ERROR** | 예상 못 한 문제. 사람이 봐야 한다 | 처리 안 된 예외(500), 결제 금액 불일치, 대사에서 복구 불가 데이터 |
| **WARN** | 예상한 실패. 시스템이 스스로 처리했다 | LLM 타임아웃 후 FALLBACK, 서킷브레이커 OPEN, 포트원 응답 지연 |
| **INFO** | 비즈니스 흐름의 이정표 | 견적 발급, 주문 생성, 결제 승인, 상태 전이, 스케줄러 시작·종료(처리 건수) |
| **DEBUG** | 개발 중 상세 정보 | SQL, LLM 요청·응답 전문 |
| **TRACE** | 아주 세세한 정보 | `jdbc.bind` (바인딩 값) |

운영(prod)에서는 **root를 INFO**로 둔다. DEBUG와 TRACE는 켜지 않는다.

### 파트별 가이드

| 파트 | 남길 것 | 피할 것 |
|---|---|---|
| 채팅·LLM | INFO: 의도 분류, 견적 수정 결과 / WARN: 타임아웃, 재시도, FALLBACK | 프롬프트·응답 본문 |
| 견적 | INFO: 발급, 만료, 수정 완료 / WARN: 품목 제외와 사유 | 품목 목록 전체 |
| 주문 | INFO: 생성, 상태 전이 / WARN: 주문 거부와 사유 | 배송지, 연락처 |
| 결제(포트원) | INFO: 요청 결과 요약(`paymentId`, 상태) / WARN: 외부 API 실패 / ERROR: 금액 불일치 | 카드 정보, 인증키 |
| 스케줄러·대사 | INFO: 시작, 종료, 처리 건수 / ERROR: 복구 불가 | 건마다 INFO |
| 인증 | INFO: 로그인 성공(userId만) / WARN: 토큰 재사용 감지 | 토큰 값, 소셜 로그인 코드 |

## 4. 예외는 한 곳에서만 처리한다

### GlobalExceptionHandler가 하는 일 (적용됨)

예외는 던져지면 위로 올라가 `GlobalExceptionHandler`에서 끝납니다. 핸들러는 응답을 만들면서 **로그와 집계를 같이** 합니다.

| 예외 | 로그 레벨 | 스택트레이스 | 집계 (`zelo.api.error`의 `code` 태그) |
|---|---|---|---|
| `BusinessException` (4xx) | INFO | 안 남김 | 예외의 에러 코드 |
| `BusinessException` (5xx, 예: `PG_TIMEOUT`, `AI_*`) | WARN | 안 남김 | 예외의 에러 코드 |
| `RateLimitExceededException` (횟수 제한) | INFO | 안 남김 | 예외의 에러 코드 |
| 검증 실패, body 누락, 타입 변환 실패 | INFO | 안 남김 | `INVALID_INPUT` |
| Redis 장애 | ERROR | 남김 | `SERVICE_UNAVAILABLE` |
| `Exception` (처리 안 된 예외, 500) | ERROR | **남김** | `INTERNAL_ERROR` |

- JWT 인증 실패(401)와 권한 없음(403)도 `JwtAuthenticationEntryPoint`, `JwtAccessDeniedHandler`가 이 핸들러로 넘기므로 자동으로 집계된다.
- Grafana에서는 `zelo_api_error_total{code="..."}`로 본다. 예: 로그인 제한 횟수 `code="AUTH_014"`, 서버 오류 `code="COMMON_002"` 식으로 에러 코드별 추이를 볼 수 있다.
- **핸들러를 거치지 않는 응답은 세지 않는다.** 예외를 삼키거나 FALLBACK으로 처리한 실패가 그렇다. 이런 실패는 직접 센다.
- `BusinessException`은 5xx 상태도 가진다. 그래서 "4xx는 INFO, 나머지는 ERROR"로 나누지 않고, **예상한 예외(`BusinessException`)는 INFO/WARN, 예상 못 한 예외(`Exception`)만 ERROR**로 나눈다. 안 그러면 외부 서비스 지연처럼 이미 처리한 실패가 ERROR로 쌓인다.

### 서비스 코드에서 로그를 찍는 경우

| 상황 | 로그를 찍는가 |
|---|---|
| 예외를 **던지기만** 한다 (`throw`) | **찍지 않는다.** 핸들러가 찍는다 |
| 같은 예외를 **그대로 던진다** | **찍지 않는다.** 핸들러가 찍는다 |
| 원래 예외를 `BusinessException`으로 **바꿔서 던진다** | **원래 예외를 `catch`에서 한 번 찍는다.** `BusinessException`은 원인을 담을 수 없어 원래 예외가 여기서 사라지기 때문이다 |
| 예외를 **잡고 삼킨다** (다시 안 던진다) | **반드시 찍는다.** 위로 안 올라가서 아무도 모르게 사라진다 |

잡고 삼키는 대표적인 곳: 스케줄러, 메일 발송, 멱등 키 정리 같은 보조 작업.

`BusinessException`에는 원인(`cause`)을 담는 생성자가 없습니다. 그래서 다른 예외를 `BusinessException`으로 바꿔 던질 때는 바꾸기 전에 `catch`에서 원래 예외를 남깁니다. 이때 핸들러는 에러 코드와 메시지만 찍고 스택트레이스는 찍지 않으므로 같은 스택트레이스가 두 번 쌓이지 않습니다. 둘은 같은 traceId로 묶여 한 번에 검색됩니다.

```java
// 나쁜 예 1: 같은 예외를 찍고 다시 던진다. GlobalExceptionHandler가 또 찍어서 중복된다
catch (BusinessException e) {
    log.error("결제 실패", e);
    throw e;
}

// 나쁜 예 2: 원래 예외를 버리고 바꿔 던진다. 왜 실패했는지 어디에도 안 남는다
catch (SocketTimeoutException e) {
    throw new BusinessException(ErrorCode.PG_TIMEOUT);
}

// 좋은 예: 사라질 원래 예외를 catch에서 한 번 남기고, 바꿔서 던진다
catch (SocketTimeoutException e) {
    log.warn("포트원 응답 지연 portoneId={}", portoneId, e);
    throw new BusinessException(ErrorCode.PG_TIMEOUT);
}
```

### 실패를 중복으로 세지 않는다

핸들러까지 올라가는 실패는 이미 `zelo.api.error`로 센다. 같은 실패를 서비스에서 `BusinessMetrics`로 또 세면 그래프가 두 배로 보인다.

```java
// 나쁜 예: 던지는 실패를 서비스에서 또 센다 (핸들러가 이미 센다)
businessMetrics.count(BusinessMetric.ORDER_REJECTED, "reason", "INSUFFICIENT_STOCK");
throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);

// 좋은 예: 던지기만 한다. 코드별 횟수는 zelo.api.error에서 본다
throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
```

예외 없이 대체 처리한 실패(`CHAT_FALLBACK`처럼 핸들러에 안 닿는 경우)는 직접 센다.

### 스택트레이스를 남기는 기준

스택트레이스는 `at com.server.zelo...`로 이어지는 호출 경로 줄들입니다. 로그 마지막 인자로 **예외 객체를 넘기면** 찍히고, 안 넘기면 안 찍힙니다.

```java
log.error("서버 오류 발생", e);              // 스택트레이스 찍힘
log.info("비즈니스 예외 code={}", code);     // 안 찍힘
```

| 상황 | 스택트레이스 |
|---|---|
| 에러 코드만 봐도 원인을 안다 (사용자 입력 오류, 예상한 거부) | 불필요 |
| 원인을 모르고 코드의 어디서 났는지 봐야 한다 (예상 못 한 예외) | 필요 |

스택트레이스는 한 건에 수십 줄이라 용량이 크고, 불필요하게 남기면 진짜 오류가 묻힙니다.

## 5. 절대 로그에 남기지 않는 것

- JWT, 리프레시 토큰, 비밀번호, API 키, 소셜 로그인 코드
- 이메일, 전화번호, 배송지 등 개인정보
- 카드 정보
- LLM 프롬프트·응답 전문 (INFO 이상)

Loki는 검색이 쉬워서 한번 들어가면 누구나 볼 수 있습니다. 로그에는 `userId`, `orderId`, `paymentId` 같은 식별자만 남깁니다.

## 6. 작성 전 체크리스트

1. 실패를 예외로 던지기만 하는가? → 로그도 지표도 추가하지 않는다 (핸들러가 한다)
2. 성공 횟수나 시간이 궁금한가? → `BusinessMetrics` (태그에 `userId` 같은 값을 넣지 않았는가?)
3. 한 건의 이정표나 원인이 궁금한가? → 로그 (인증 전 요청이면 `userId`를 본문에 적었는가?)
4. 원래 예외를 `BusinessException`으로 바꿔 던지는가? → 바꾸기 전에 `catch`에서 원래 예외를 한 번 찍는다
5. 예외를 삼키거나 FALLBACK으로 대체 처리하는가? → 로그를 찍고, 횟수는 직접 센다
6. 같은 실패를 핸들러와 서비스에서 둘 다 세고 있지는 않은가?
7. 레벨: 사람이 봐야 하면 ERROR, 예상했고 처리했으면 WARN, 이정표면 INFO
8. 개인정보나 토큰이 들어가지 않았는가?
9. 컨트롤러 테스트에서 `GlobalExceptionHandler`를 `BusinessMetrics`와 함께 만들었는가?
