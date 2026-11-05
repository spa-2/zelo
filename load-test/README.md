# 부하 테스트 가이드

k6로 API에 요청을 보내서 "서버가 얼마나 빠르게, 얼마나 많이 버티는지" 재는 스크립트 모음이다.
결과는 `docs/load-test/results/` 에 파일로 남는다.

```
load-test/
├── README.md                  이 문서
├── run-k6.sh                  k6를 도커로 실행하는 스크립트
├── collect-metrics.sh         테스트 중 서버 상태(CPU, 스레드 등)를 기록하는 스크립트
├── quote/
│   └── quote-issue.js         (기존) 견적 발급 부하 테스트
└── auth/
    ├── login-smoke.js         이메일 로그인 스모크 테스트 (기준선 측정)
    └── login-stress.js        이메일 로그인 스트레스 테스트 (한계 찾기)
docs/load-test/results/        결과 파일이 생기는 곳
```

## 1. 준비물

| 준비 | 방법 |
|---|---|
| k6 | **설치하지 않고 도커로 실행한다.** Docker Desktop만 켜져 있으면 되고, 이미지(`grafana/k6`)는 처음 실행할 때 자동으로 받는다 |
| DB, Redis | `docker compose up -d --wait` |
| 더미 데이터 | 사용자 3만 명이 들어 있어야 한다. 확인: `select count(*) from users;` (30000 이상이면 정상) |
| 앱 실행 | 아래 2번 참고. **SQL 로그를 꺼야 한다** |

## 2. 실행 방법

터미널을 3개 쓴다. 명령은 모두 **저장소 루트**(`zelo/`)에서 실행한다.
(루트가 아니면 결과 파일이 엉뚱한 위치에 생긴다.)

**터미널 1: 앱 실행 (SQL 로그 끄기)**

```bash
./gradlew bootRun --args='--logging.level.org.hibernate.SQL=info --logging.level.org.hibernate.orm.jdbc.bind=info'
```

SQL 로그가 켜져 있으면 요청마다 로그를 찍느라 느려져서 서버의 진짜 속도가 아닌 로그 속도를 재게 된다.

**터미널 2: 서버 지표 기록 (선택이지만 병목을 찾으려면 필요)**

```bash
load-test/collect-metrics.sh
```

테스트가 끝나면 `Ctrl+C`로 끈다. 5초마다 CPU, Tomcat 스레드, Hikari 커넥션 풀, 힙 메모리를 `docs/load-test/results/server-metrics-*.tsv`에 쌓는다.
(앱의 지표 포트는 9292다. 다르면 `METRICS_URL=http://localhost:포트/actuator/prometheus load-test/collect-metrics.sh`)

**터미널 3: k6 실행**

```bash
# 먼저 짧게 돌려서 스크립트가 동작하는지 확인 (워밍업 5초 + 측정 10초)
load-test/run-k6.sh load-test/auth/login-smoke.js -e WARMUP=5s -e DURATION=10s

# 진짜 측정 (워밍업 20초 + 측정 1분)
load-test/run-k6.sh load-test/auth/login-smoke.js -e NOTE="로컬 첫 측정"
```

`run-k6.sh`는 `docker run grafana/k6 run ...`을 대신 길게 써 주는 스크립트다.
- 컨테이너 안의 `localhost`는 내 컴퓨터가 아니라서 앱 주소를 `http://host.docker.internal:8080`으로 바꿔 둔다.
- 저장소 폴더를 컨테이너에 연결해서 결과 파일이 내 컴퓨터의 `docs/load-test/results/`에 그대로 생긴다.
- 커밋 해시(`COMMIT`)는 자동으로 넣는다.
- 스크립트 경로 뒤에 `-e 이름=값`을 붙이면 그대로 k6에 전달된다.
- 도커로 돌리면 컨테이너 네트워크를 한 번 거쳐서 응답 시간이 아주 조금 늘 수 있다. 문서에 "k6를 도커로 실행"이라고 적어 둔다.
- k6 버전을 고정하려면 `K6_IMAGE=grafana/k6:버전 load-test/run-k6.sh ...`로 실행한다.

`-e 이름=값`으로 설정을 바꿀 수 있다.

| 환경변수 | 기본값 | 의미 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | 테스트할 서버 주소 |
| `USER_COUNT` | `21000` | 로그인에 쓸 사용자 수 (이메일 사용자는 1~21000번) |
| `PASSWORD` | `Zelo1234!` | 더미 사용자 공통 비밀번호 |
| `WARMUP` | `20s` | 워밍업 시간 (측정에서 제외) |
| `DURATION` | `1m` | 측정 시간 |
| `COMMIT`, `NOTE` | | 결과 문서에 적히는 커밋 해시와 메모 |
| `RESULT_DIR` | `docs/load-test/results/auth` | 결과 저장 위치 |

## 3. 결과는 어디에 생기나

테스트가 끝나면 `docs/load-test/results/`에 파일이 생긴다.

| 파일 | 내용 |
|---|---|
| `auth-login-smoke-시각.md` | **사람이 읽는 결과 문서.** 결과 표와 합격 판정이 채워져 있고, 서버 지표, 환경, 해석 메모는 직접 채운다 |
| `auth-login-smoke-시각.json` | k6 원본 데이터. 나중에 다시 분석할 때 쓰고 필요 없으면 지워도 된다 |
| `server-metrics-시각.tsv` | 2번 터미널의 서버 지표 기록. 시간별 CPU, 스레드 등 |

결과 문서(.md)를 열어서 "서버 지표" 칸을 `server-metrics-*.tsv`의 최대, 평균값으로 채우면 한 건의 결과 정리가 끝난다.

## 4. 스크립트(`login-smoke.js`) 읽는 법

스크립트는 4구역으로 되어 있다. 주석에도 번호가 붙어 있다.

| 구역 | 하는 일 | 알아둘 점 |
|---|---|---|
| 1. 설정값 | 서버 주소, 사용자 수 같은 값을 정한다 | `__ENV.이름 \|\| 기본값`은 "환경변수가 있으면 그 값, 없으면 기본값"이라는 뜻이다 |
| 2. 시나리오(`options`) | 언제, 누가, 얼마나 요청을 보낼지 정한다 | 워밍업(`warmup`)과 측정(`smoke`) 두 구간으로 나눠 놓았다. `thresholds`는 합격 기준이다 |
| 3. 실제 요청(`default function`) | 가상 사용자가 반복해서 실행하는 부분 | 반복 번호로 사용자를 순서대로 골라 로그인한다. `check`는 응답이 정상인지 확인한다 |
| 4. 결과 저장(`handleSummary`) | 테스트가 끝나면 결과를 파일로 쓴다 | `.md`(읽는 용), `.json`(원본)이 만들어진다 |

**용어**

| 용어 | 뜻 |
|---|---|
| VU | 가상 사용자. 요청을 보내고, 응답을 받으면 다음 요청을 보내는 반복문 하나다 |
| 스모크 테스트 | VU 1명으로 아주 가볍게 돌려서, 정상 동작하는지와 부하가 없을 때의 속도(기준선)를 확인하는 테스트 |
| 워밍업 | JVM과 커넥션 풀이 데워질 때까지 먼저 돌리는 구간. 이 구간의 느린 요청이 결과에 섞이지 않게 측정에서 뺀다 |
| p95 | 요청의 95%가 이 시간 안에 끝났다는 뜻. 평균보다 느린 쪽을 잘 보여 준다 |
| RPS | 초당 요청 수 |
| threshold | 합격 기준. 어기면 k6가 "실패"로 표시한다 |
| `phase` 태그 | 결과를 워밍업과 측정으로 나눠 보려고 요청마다 붙이는 이름표 |

## 5. 각 파트에서 내 스크립트를 만들 때

`auth/login-smoke.js`를 복사해서 `load-test/{도메인}/{시나리오}.js`로 만들고, 아래만 바꾸면 된다.

**바꿔야 할 곳**

| 위치 | 바꿀 내용 |
|---|---|
| 맨 위 주석 | 목적과 실행 명령 |
| 구역 1 설정값 | 사용자 범위(`USER_COUNT` 등)와 필요한 값 |
| 구역 3 요청 | 호출할 API 주소, 본문, `check`의 검증 조건, `tags: { name: '...' }` |
| 구역 4 `filePrefix` | 결과 파일 이름 앞부분(`auth-login-smoke` → `order-create-smoke` 등) |
| `buildReport` 안의 제목과 조건 문구 | 내 API에 맞게 |

**그대로 두는 것 (팀 공통 규칙)**

- 워밍업과 측정을 나누는 구조(`phase: 'warmup'` / `'smoke'`)와 `thresholds`가 `{phase:smoke}`만 보는 것
- 결과를 `docs/load-test/results/`에 `{도메인}-{시나리오}-{시각}.md`로 남기는 것
- 결과 문서의 항목 구성(조건, 결과 표, 합격 판정, 서버 지표, 환경, 해석 메모)

**지킬 것**

- **사용자 번호 범위를 파트별로 나눠서 데이터가 섞이지 않게 한다.** 예: 인증 1~21000(이메일 로그인), 주문은 다른 구간. 같은 사용자를 여러 VU가 동시에 쓰면 리프레시 토큰이 서로 덮어써진다.
- **인증이 필요한 API는 토큰이 필요하다.** 액세스 토큰은 기본 10분이라 길게 도는 테스트는 중간에 401이 난다. 로컬에서는 `jwt.access-expiration`을 60분으로 늘려서 실행한다.
- **외부 서비스(LLM, 포트원, 소셜, 메일)는 fake로 돌리고, 실제 평균 응답 시간만큼 sleep을 준다.** 소셜 로그인 fake는 `fake-` 로 시작하는 code를 그대로 사용자 식별자로 쓴다.
- **SQL 로그를 끈다**(2번 참고).
- **측정은 한 번에 하나만.** 다른 사람의 테스트나 무거운 프로그램이 동시에 돌면 결과가 흔들린다.
- **IP 단위 제한(이메일 전송, 비밀번호 재설정 등)은 k6의 IP가 하나라서 429가 날 수 있다.** 로컬에서는 요청마다 `X-Forwarded-For` 헤더를 다르게 넣어 우회한다(동작 확인 필요).

## 6. 스트레스 테스트 (`auth/login-stress.js`)

초당 요청 수(RPS)를 단계적으로 올리면서 **서버가 처음 무너지는 단계**와 **그때 무엇이 막혔는지**를 찾는다.

```bash
# 먼저 짧게 동작 확인 (3단계, 약 40초). 이 숫자는 결과로 쓰지 않는다
load-test/run-k6.sh load-test/auth/login-stress.js -e RATES=1,5,10 -e STEP=10s -e RAMP=2s -e WARMUP=3s

# 진짜 측정 (기본: 0.44 → 2 → 5 → 10 → 20 → 40 → 60 → 80 → 100 → 130 → 160 RPS, 한 단계 60초)
load-test/run-k6.sh load-test/auth/login-stress.js -e NOTE="로컬 첫 스트레스"
```

- **서버 지표는 k6가 직접 읽는다.** 앱의 지표 주소(`host.docker.internal:9292`)를 2초마다 읽어서 단계별 CPU, Tomcat 스레드, Hikari 커넥션 최대값을 결과 표에 넣는다. 그래서 스트레스에서는 `collect-metrics.sh`를 따로 켜지 않아도 된다.
- **멈추는 기준**: 단계별 p95가 `P95_LIMIT_MS`(기본 500ms)를 넘거나 실패율이 `ERROR_LIMIT`(기본 1%)를 넘으면 그 자리에서 멈춘다. 멈출 때 `thresholds ... were crossed ... stopping test prematurely` 메시지와 종료 코드 99가 나오는데 **정상 신호**다(한계를 찾았다는 뜻).
- **모든 단계를 통과하면** 한계가 마지막 단계(160 RPS)보다 높다는 뜻이다. 이때는 `-e RATES=...`로 더 높은 값을 넣어 다시 돌린다.
- **결과 문서**(`auth-login-stress-시각.md`)에서 볼 곳은 맨 위 "한계 판정"과 "단계별 결과" 표다.
  - 마지막으로 통과한 단계 = 지금 서버가 안정적으로 처리하는 RPS
  - 처음 실패한 단계의 서버 지표 단서 = 병목 후보 (CPU 포화, Hikari 대기, 스레드 한도 중 무엇인지)
  - `dropped_iterations`가 0보다 크면 k6가 약속한 속도를 못 낸 것이므로, 서버가 아니라 k6나 내 컴퓨터가 한계였을 수 있다.
- **속도 값은 분당 요청 수로 바꿔서 쓴다.** k6의 속도는 정수만 받아서, 0.44 RPS는 분당 26건(`timeUnit: '1m'`)으로 보낸다.
- 스크립트의 환경변수

| 환경변수 | 기본값 | 의미 |
|---|---|---|
| `RATES` | `0.44,2,5,10,20,40,60,80,100,130,160` | 단계별 목표 RPS (쉼표로 구분) |
| `STEP` | `60s` | 한 단계의 길이 |
| `RAMP` | `5s` | 단계가 바뀔 때 속도를 올리는 시간 (`STEP`보다 짧아야 함) |
| `WARMUP`, `WARMUP_RATE` | `20s`, `5` | 시작 전 워밍업 시간과 속도 |
| `P95_LIMIT_MS`, `ERROR_LIMIT` | `500`, `0.01` | 멈추는 기준 |
| `PRE_VUS`, `MAX_VUS` | `20`, `400` | 미리 만들어 둘 VU 수와 최대 VU 수 |
| `MONITOR_INTERVAL` | `2` | 서버 지표를 읽는 간격(초) |
| `METRICS_URL` | `http://host.docker.internal:9292/actuator/prometheus` | 앱의 지표 주소 |

**스모크와 스트레스의 차이**: 스모크는 VU 1명이 쉬지 않고 보내서 기준선(p95)을 얻는다. 스트레스는 VU가 아니라 **초당 요청 수를 약속**하고, 서버가 느려지면 k6가 VU를 늘려서라도 그 속도를 지킨다. 그래서 서버가 못 따라가는 지점이 정확히 드러난다.

**부하 테스트**(목표 RPS 0.44를 5~10분 유지)는 스트레스에서 한계를 확인한 뒤에 만든다. VU는 **올림(목표 RPS × 스모크 p95(초)), 최소 1**로 계산한다.

## 7. 자주 겪는 문제

| 증상 | 원인과 해결 |
|---|---|
| `Cannot connect to the Docker daemon` | Docker Desktop을 켠다 |
| `could not open '...': no such file or directory` (결과 저장 실패) | `RESULT_DIR`로 지정한 폴더가 없다. k6는 폴더를 만들지 않는다. 기본 폴더는 `run-k6.sh`가 만들어 준다 |
| 요청이 전부 실패, `connection refused` | 앱이 안 떠 있거나 `BASE_URL`이 틀렸다 |
| 상태 코드 401/400이 나온다 | 더미 데이터가 없거나 비밀번호가 다르다. `select count(*) from users;`와 `PASSWORD`를 확인한다 |
| 429가 나온다 | 로그인 실패가 쌓여 시도 횟수 제한에 걸렸다. Redis의 `LOGIN_ATTEMPT:*` 키를 지우거나 10분을 기다린다 |
| 결과 파일이 엉뚱한 곳에 생긴다 | 저장소 루트가 아닌 곳에서 실행했다 |
| `collect-metrics.sh` 값이 `-`로 나온다 | 앱의 지표 주소가 다르다(`management.server.port`)거나 앱이 안 떠 있다 |
