# 결제창(KG이니시스) 로컬 테스트 가이드

프론트 없이 로컬에서 **주문하기 → KG이니시스 결제창 → 결제 확인**까지 실제 포트원 테스트 채널로 확인하는 방법이다.

- API 흐름만 볼 때는 `http/zelo-scenario.http`를 쓴다. 포트원 fake 모드라 결제창 없이 바로 결제 완료된다.
- 결제창까지 볼 때는 이 폴더의 테스트 페이지를 쓴다. 포트원 real 모드로 실제 테스트 채널을 호출한다.

---

## 0. 한눈에 보기

| 단계 | 할 일 | 한 번만? |
|---|---|---|
| 1 | Docker로 Postgres·Redis 실행 | 매번 |
| 2 | `application-local.yaml` 만들기 | 한 번 |
| 3 | IntelliJ 실행 구성에 `local` 프로필 지정 | 한 번 |
| 4 | 서버 실행 → 테스트 페이지에서 회원가입·견적·주문하기 | 매번 |

이 폴더의 파일

| 파일 | 내용 |
|---|---|
| `order-pay.html` | 회원가입·로그인 → 견적 받기 → 주문하기(결제창) → 결제 확인을 한 페이지에서 실행 |
| `config.js` | 포트원 상점 ID·채널 키. 프론트에 노출되는 값이라 비밀이 아니다. **API Secret은 넣지 않는다** |
| `README.md` | 이 가이드 |

`http/` 폴더는 서버 빌드(jar)에 들어가지 않는다. `local` 프로필로 실행할 때만 서버가 이 폴더를 서빙하므로 운영에 배포되지 않는다.

---

## 1. Docker 실행

Docker Desktop을 켜고 **Engine running**이 될 때까지 기다린 뒤 프로젝트 폴더에서 실행한다.

```bash
docker compose up -d --wait
```

`docker ps`에 `zelotest-postgres`, `zelotest-redis`가 `Up (healthy)`이면 된다.

> `failed to connect to the docker API ... dockerDesktopLinuxEngine` 에러는 Docker Desktop이 꺼져 있다는 뜻이다.

---

## 2. `application-local.yaml` 만들기

`src/main/resources/application-local.yaml`을 새로 만든다. `application.yaml`의 `${...}` 자리에 들어갈 값과 로컬에서만 바꿀 설정을 둔다.

- **`application.yaml`은 수정하지 않는다.**
- `.gitignore`에 등록돼 있어 커밋되지 않는다. 커밋 전에 `git status`에 이 파일이 보이지 않는지 확인한다.

```yaml
# 로컬 실행 전용 설정. .gitignore에 등록돼 있어 커밋되지 않는다
# IntelliJ 실행 구성의 "활성화된 프로파일"에 local을 넣고 실행한다

spring:
  web:
    resources:
      # local 프로필에서만 결제 테스트 페이지(http/payment-test)를 서빙한다. 운영 설정에는 없어서 배포되지 않는다
      static-locations: classpath:/static/, file:./http/payment-test/

# application.yaml의 ${jwt_secret}. Base64로 디코딩해 32바이트 이상인 값 (아래는 CI용 더미 값)
jwt_secret: Y2ktZHVtbXktand0LXNlY3JldC1rZXktMzJieXRlcy1sb25nISE=

# 견적(채팅)에 필요. 팀 OpenAI 키를 넣는다
OPENAI_API_KEY: 여기에_OpenAI_키

# 소셜 로그인 키. 더미면 소셜 로그인만 안 되고 일반 회원가입·로그인은 된다
GOOGLE_CLIENT_ID: dummy-google-client-id
GOOGLE_CLIENT_SECRET: dummy-google-client-secret
KAKAO_CLIENT_ID: dummy-kakao-client-id
KAKAO_CLIENT_SECRET: dummy-kakao-client-secret

zelo:
  llm:
    # fake면 견적 카드가 만들어지지 않는다. 결제 테스트는 real + 실제 OpenAI 키가 필요하다
    mode: real

  seed:
    # 상품·재고·리뷰 시드. 상품이 없으면 견적을 만들 수 없다
    enabled: true

  portone:
    # 포트원 테스트 채널 값 (팀 DM으로 공유)
    mode: real
    store-id: 여기에_상점_ID
    api-secret: 여기에_API_Secret
    # 웹훅 URL을 등록하면 받는다. 비워 두면 웹훅만 거절되고 결제 확인 API는 동작한다
    webhook-secret:
```

| 값 | 비밀 여부 | 받는 곳 |
|---|---|---|
| OpenAI 키 | **비밀** | 팀 DM |
| 포트원 상점 ID | 비밀 아님 (프론트에 노출되는 값) | 팀 DM 또는 `config.js` |
| 포트원 API Secret | **비밀** | 팀 DM. 채팅·노션·커밋에 올리지 않는다 |
| 웹훅 시크릿 | **비밀** | 웹훅 URL 등록 후 발급 (선택, 7번 참고) |
| `jwt_secret` | 로컬은 위 더미 값 사용 | - |

> 키를 `application.yaml`의 `${...}` 안에 직접 넣으면 안 된다. `${값}`은 "이 이름의 설정을 찾아라"라는 뜻이라 `Could not resolve placeholder` 에러가 난다.

---

## 3. IntelliJ 실행 구성에 `local` 프로필 지정

1. 오른쪽 위 실행 목록 → **구성 편집(Edit Configurations...)**
2. `ZeloApplication` 선택 → **활성화된 프로파일(Active profiles)** 칸에 `local` 입력
   - 칸이 안 보이면 **옵션 수정(Modify options)** 에서 켠다
3. **확인** 후 실행

실행 로그 위쪽에 아래 줄이 나오면 성공이다.

```
The following 1 profile is active: "local"
```

> `Could not resolve placeholder 'jwt_secret'` 에러가 나면 `local` 프로필이 적용되지 않은 것이다.

---

## 4. 테스트 페이지로 결제하기

1. IntelliJ에서 `local` 프로필로 서버 실행
2. 브라우저에서 열기

   ```
   http://localhost:8080/order-pay.html
   ```

3. **[회원가입 후 로그인]** — 이메일은 자동으로 새 값이 채워진다. 가입할 때 기본 배송지도 같이 만들어진다. 이미 가입한 이메일이면 **[로그인]**
4. **[견적 받기]** — roomId, quoteId가 자동으로 채워진다 (LLM 응답까지 몇 초)
5. **[주문하기]** — 구매 확인 → 주문 생성 → **KG이니시스 결제창** → 결제 → 결제 확인
6. 진행 기록 마지막 줄이 아래면 성공

   ```
   4. 결제 확인: PAID (결제가 완료됐습니다.)
   ```

DB에서 확인할 것: `payments.status = PAID`, `orders.status = PAID`, `deliveries.status = READY`, 채팅방 결제 카드 메시지

결제 후 환불까지 확인하려면 Postman 등으로 호출한다.

```
POST http://localhost:8080/api/orders/{orderId}/refund
Authorization: Bearer {토큰}
{ "reason": "단순 변심" }                                          // 남은 상품 전부 환불
{ "reason": "일부 환불", "items": [{ "orderItemId": 1, "quantity": 1 }] }  // 부분 환불
```

---

## 5. 실제 프론트에서 결제창을 여는 방법

이 페이지는 테스트용 대역이다. 실제 프론트도 사용자는 **[주문하기] 버튼만** 누르고, 아래 순서로 호출한다.

```js
const confirmation = await api.post("/api/orders/confirmations", { roomId, quoteId });
const order = await api.post("/api/orders", { quoteId, confirmationId: confirmation.confirmationId });

const payment = await PortOne.requestPayment({
  storeId: STORE_ID,              // 프론트 환경 변수에 고정
  channelKey: CHANNEL_KEY,        // 프론트 환경 변수에 고정
  paymentId: order.portoneId,     // 주문 생성 응답
  orderName: order.orderName,
  totalAmount: order.totalAmount,
  currency: "CURRENCY_KRW",
  payMethod: "CARD",
  // KG이니시스 V2는 구매자 이메일·이름·휴대폰이 없으면 결제창을 열지 않는다
  customer: { email: me.email, fullName: me.name, phoneNumber: me.phoneNumber },
});

// 결제창이 닫히면 성공·실패와 상관없이 서버에 확인을 요청한다 (서버가 포트원에 다시 조회해서 반영)
const result = await api.post(`/api/payments/${order.portoneId}/confirm`);
```

실제 프론트는 다른 주소(예: `localhost:5173`)에서 돌아가므로 백엔드 CORS 설정이 필요하다. 이 테스트 페이지는 백엔드와 같은 주소라 CORS가 필요 없다.

---

## 6. 자주 만나는 문제

| 증상 | 원인 / 해결 |
|---|---|
| `failed to connect to the docker API` | Docker Desktop이 꺼져 있음 |
| `Could not resolve placeholder 'jwt_secret'` | `local` 프로필이 적용 안 됨 (3번) |
| `localhost:8080/order-pay.html`이 404 | `application-local.yaml`에 `static-locations` 설정이 없거나, 실행 작업 디렉터리가 프로젝트 루트가 아님 |
| 로그인 401 `MEMBER_003` | 가입 안 한 이메일. **[회원가입 후 로그인]** 을 누른다 |
| "견적 카드가 없어요" | OpenAI 키가 더미이거나 `llm.mode: fake` |
| 주문 생성 시 상품·재고 에러 | 시드가 안 들어감 (`seed.enabled: true`) |
| 결제창 "구매자 이메일은 필수 입력" | `customer`(email·fullName·phoneNumber) 누락 |
| 결제창 "변수(oid)의 값에 길이 문제" | 결제 ID가 40자를 넘음. 결제 ID 40자 수정이 반영된 dev를 받는다 |
| 결제창이 안 뜸 | 브라우저 팝업 차단 해제 |
| 페이지를 고쳤는데 반영이 안 됨 | `Ctrl + Shift + R`로 강력 새로고침 |
| 결제 확인이 `CONFIRM_PENDING` | 결제창에서 결제를 끝내지 않음 |
| 테스트 로그에 `Caused by: SocketTimeoutException` | 장애 상황을 일부러 만드는 테스트의 로그. 테스트 결과에 FAILED가 없으면 정상 |

---

## 7. (선택) 웹훅까지 테스트

포트원이 우리 서버로 웹훅을 보내려면 외부 주소가 필요하다. **한 사람 PC에서만** 설정한다.

1. ngrok 설치·로그인 후 실행

   ```bash
   ngrok http 8080
   ```

2. 포트원 콘솔(테스트 모드) → 결제 연동 → 웹훅 → URL `https://(ngrok주소)/api/payments/webhook` 등록
3. 발급된 웹훅 시크릿을 `application-local.yaml`의 `webhook-secret:`에 넣고 서버 재시작
4. 결제 1건 후 `webhook_events`에 `PROCESSED` 행이 생기는지, `event_type`이 `Transaction.Paid`인지 확인

> smee.io는 본문을 다시 직렬화해서 서명 검증(401)에 실패할 수 있다. ngrok·cloudflared를 쓴다.

---

## 8. 주의

- **KG이니시스 테스트 MID는 실제 카드로 승인되고 당일 밤 자동 취소된다.** 소액 상품으로 테스트하고, 끝나면 환불 API로 직접 환불해 본다.
- 같은 결제 ID로 결제창을 다시 열면 에러가 날 수 있다. 주문을 새로 만든다.
- `application-local.yaml`은 **커밋하지 않는다.**
- `config.js`에는 상점 ID·채널 키만 넣는다. API Secret·웹훅 시크릿은 서버 비밀 값이라 넣지 않는다.
