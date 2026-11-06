# 포트원 지연 장애 테스트 (개선 전)

## 한 줄 요약
포트원이 5초씩 느려지자 결제 요청이 서버 스레드 200개를 전부 차지했고, **결제와 상관없는 상품 조회까지 39ms → 1.75초로 느려졌다.**

## 무엇을 했나
| 항목 | 내용 |
|---|---|
| 상황 | 포트원(PG) 응답이 느려진 장애 상황 |
| 포트원 지연 | 결제 조회 5초, 빌링키 결제 5초 (평소 63ms, 773ms) |
| 결제 | 목표의 5배 → 10배 → 15배 → 20배로 1분씩 올림 |
| 상품 조회 | 평소 목표(41.7 RPS)를 같은 시간에 계속 보냄 |
| 조건 | 로컬, performance 프로필 (커넥션 풀 30, Tomcat 스레드 200), 2026-11-05 |

## 결과
### 1. 상품 조회가 느려졌다
| 지표 | 평소 | 포트원 지연 중 |
|---|---|---|
| 상품 조회 p95 | 39ms | **1.75초** |
| 실패 | 0% | 0% (느려졌을 뿐 에러는 없음) |

### 2. 원인: 결제가 스레드를 다 썼다
| 결제 | Tomcat 스레드 사용 (평균 / 최대, 200개 중) |
|---|---|
| 목표 × 5 | 48 / 60 |
| 목표 × 10 | 117 / 174 |
| 목표 × 15 | 166 / 188 |
| 목표 × 20 | 192 / **200 (가득 참)** |
| 결제가 끝난 뒤 | 1~2 (회복) |

- 결제 요청 하나가 포트원 응답을 기다리는 5~10초 동안 스레드 1개를 붙잡는다
- 스레드 200개는 모든 API가 같이 쓴다. 결제가 다 쓰면 상품 조회는 빈 스레드가 날 때까지 줄을 선다
- DB 커넥션은 30개 중 2개만 썼다. 병목은 DB가 아니라 스레드다

### 3. 결제 데이터는 맞았다
- 결제 성공(PAID) 4,345건 = 보낸 결제 수, 음수 재고 0건

## 결론
| 항목 | 내용 |
|---|---|
| 문제 | 외부 API(포트원) 하나가 느려지면 서버 전체가 같이 느려진다 (장애 전파) |
| 개선 | ① 포트원 동시 호출 수 제한 (벌크헤드) ② 실패·지연이 계속되면 잠시 호출을 막는 서킷 브레이커 |
| 개선 후 목표 | 같은 조건에서 상품 조회 p95가 평소 수준(약 40ms)을 유지 |

## 참고
- fake 포트원은 기다린 뒤 성공한다. 실제 포트원이면 5초(읽기 타임아웃)에서 에러가 난다
- 상품 결과는 5분 전체 값이다. 개선 후 비교 때는 상품 k6에 `--out csv`를 붙여 결제 단계별로 나눠 본다
- 결과 파일: `docs/load-test/results/payment/fault/` (`payment-summary.json`, `product-summary.json`, `server.csv`)

## 실행
모든 명령은 저장소 루트에서 실행한다. 터미널 4개를 쓴다

**터미널 1: 앱 (포트원 지연 5초)** (서버가 켜진 채로 있어서 이 터미널은 앱 전용)
```bash
./gradlew bootRun --args='--spring.profiles.active=local,performance --server.port=8080 --zelo.llm.mode=fake --zelo.social.mode=fake --zelo.portone.mode=fake --zelo.portone.fake-delay=5s --zelo.portone.fake-billing-delay=5s'
```
- 로그 `부하 테스트 결제 시드 저장 완료 orders=100000`이 나오면 준비 끝

**터미널 2: 서버 지표** (결제 k6 전에 켜고, 상품 k6가 끝나면 Ctrl+C)
```bash
load-test/chat/record-metrics.sh docs/load-test/results/payment/fault/server.csv
```

**터미널 3: 시드 CSV 뽑기 → 결제** (미리 로그인 뒤 `첫 단계 시작` 로그가 나온다)
```bash
docker compose exec -T postgres psql -U postgres -d zelotest -c "\copy (select o.id as order_id, o.user_id, p.portone_id, p.status from orders o join payments p on p.order_id = o.id where o.quote_id like 'LT-PAY-%' order by o.quote_id) to stdout with csv header" > load-test/payment/data/payment-orders.csv
k6 run -e MULTIPLIERS=5,10,15,20 -e MAX_VU_FACTOR=12 --summary-export=docs/load-test/results/payment/fault/payment-summary.json load-test/payment/payment-dau50k-limit.js
```

**터미널 4: 상품 조회** (터미널 3에 `첫 단계 시작`이 나오면 바로 실행)
```bash
docker exec zelotest-redis sh -c "redis-cli --scan --pattern 'productDetail*' | xargs -r redis-cli del"
k6 run -e VUS=10 --summary-export=docs/load-test/results/payment/fault/product-summary.json load-test/product/product-load.js
```

- 다시 측정할 때는 터미널 1의 앱을 재시작하고 CSV를 다시 뽑는다 (결제한 주문은 다시 못 쓴다)
