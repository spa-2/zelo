# 장애 대응 테스트 (Redis, RDS)

Redis나 DB에 장애를 일으키고, **핵심 기능이 무엇이 언제부터 언제까지 실패했는지**를 기록해서 한 장짜리 보고서(`report.html`)로 만든다.
같은 도구로 **로컬**(앱의 장애 동작 확인)과 **AWS**(실제 장애 전환 시간 측정)를 모두 돌린다. 외부 패키지 없이 파이썬 3와 bash만 쓴다.

| 파일 | 역할 |
|---|---|
| `run.sh` | 한 번의 테스트 전체: 프로브 시작 → 기준선 → 장애 발생 → 복구 대기 → 보고서 |
| `probe.py` | 핵심 기능 6개를 1초 간격으로 호출하고 결과를 CSV로 기록한다 |
| `report.py` | CSV를 읽어서 `report.html`(타임라인, 요약 표), `report.md`를 만든다 |

## 프로브: 무엇을 건드리는가

기본 시드(카테고리, 상품)와 **관리자 계정 하나**만 있으면 된다. 기능을 다 볼 필요 없이 Redis와 DB를 서로 다른 방식으로 쓰는 경로만 골랐다.

| 프로브 | 요청 | 의존 | 장애 때 기대하는 동작 |
|---|---|---|---|
| `read` | `GET /api/categories` | Redis 캐시 | Redis 장애 중에도 **성공**(DB로 우회, `zelo.redis.fallback` 증가) |
| `db_read` | `GET /api/users/me/addresses` | DB만 | Redis 장애와 무관하게 성공, DB 장애 때만 실패 |
| `login` | `POST /api/users/login` | Redis(리프레시 토큰) + DB | Redis, DB 장애 때 실패 후 자동 복구 |
| `reissue` | `POST /api/auth/reissue` | Redis | Redis 장애 때 실패. 승격 직전에 쓴 토큰이 사라지면 401 |
| `addr_create` | `POST /api/users/me/addresses` | Redis 분산락 + DB 쓰기 | 락, DB 장애 때 실패 후 자동 복구 |
| `addr_delete` | `DELETE .../addresses/{id}` | Redis 분산락 + DB 쓰기 | 위와 같음(만든 배송지를 바로 지운다) |

종료할 때 **쓰기 유실 확인**도 한다. 성공 응답(201)을 받은 배송지가 실제로 서버에 남아 있는지, 삭제 성공(204)을 받은 배송지가 남아 있지 않은지 본다. RDS Multi-AZ는 동기 복제라 유실이 0이어야 하고, ElastiCache는 비동기 복제라 `reissue`가 401이 되는 횟수(승격 직전 쓴 토큰 유실)로 확인한다.

## 로컬

장애는 docker 컨테이너를 멈췄다가 다시 켜는 것이다. **앱이 Redis나 DB가 없을 때 어떻게 동작하고 돌아오는지**를 보는 용도이고, 실제 장애 전환(주소 전환, 재연결 시간)은 AWS에서 봐야 한다.

```bash
docker compose up -d --wait
# 관리자 계정이 있어야 한다. 시드를 켜고 관리자 이메일, 비밀번호를 환경변수로 준다
ZELO_SEED_ENABLED=true ZELO_SEED_ADMIN_EMAIL=admin@test.com ZELO_SEED_ADMIN_PASSWORD='Admin1234!' \
  JWT_SECRET=... OPENAI_API_KEY=fake ./gradlew bootRun

# 다른 터미널, 저장소 루트에서
export BASE_URL=http://localhost:8080 PROBE_EMAIL=admin@test.com PROBE_PASSWORD='Admin1234!'
load-test/failover/run.sh local redis      # Redis 컨테이너를 30초 멈춤
load-test/failover/run.sh local rds        # Postgres 컨테이너를 30초 멈춤
```

## AWS (운영 환경)

**실제 장애 전환을 일으킨다.** 사용자가 거의 없을 때 하고, 알람과 Slack 알림이 울리는 것을 미리 알려 둔다(알림이 오는지도 테스트의 일부다).

```bash
export BASE_URL=https://zelo.wyshin.click PROBE_EMAIL=... PROBE_PASSWORD=...
load-test/failover/run.sh aws redis        # ElastiCache test-failover (하루 15회 제한)
load-test/failover/run.sh aws rds          # RDS 강제 failover (Multi-AZ, 1~2분)
```

- 시작 전에 `yes`를 입력해야 한다(`--yes`로 건너뜀). 필요한 권한은 `elasticache:TestFailover`, `rds:RebootDBInstance`, 조회 권한이다.
- 프로브용 계정이 운영 DB에 있어야 한다. 운영은 시드가 꺼져 있어서 관리자 계정이 자동으로 만들어지지 않는다(계정을 어떻게 만들지는 별도로 정한다).
- 한 시나리오를 **3번 반복**해서 결과가 일관적인지 본다.
- `aws-events.json`은 AWS가 기록한 failover 시작과 끝 시각이라 **가장 신뢰할 수 있는 증거**다. 프로브에서 측정한 시간과 같이 남긴다.

## 결과물

`docs/failover-test/results/<시각>-<대상>-<시나리오>/`

| 파일 | 내용 |
|---|---|
| `report.html` | 타임라인(초록 성공, 빨강 실패, 점선이 장애 발생과 복구), 응답 시간, 프로브별 표. 그대로 공유하거나 스크린샷으로 남긴다 |
| `report.md` | 같은 내용을 글로 |
| `probe.csv`, `events.csv`, `verify.json` | 원본 |
| `aws-before.txt`, `aws-after.txt`, `aws-events.json` | AWS 대상일 때: 전환 전후의 AZ 배치, AWS 이벤트 |

보고서의 "장애 후 복구까지"는 **장애를 일으킨 시각부터 마지막 실패 뒤 첫 성공까지**다. 사용자가 느낀 중단 시간이다.

## 알아 둘 점

- `login`, `reissue`는 시도 횟수 제한에 걸리지 않게 3초에 한 번만 호출한다(`--slow-every`).
- 호출이 시간 초과(기본 10초)로 매달려 있으면 그 호출이 끝날 때 실패로 기록된다. 장애 중 응답이 늦어지는 구간도 보고서에 나타난다.
- 프로브가 만든 배송지는 끝날 때 지운다. 지우지 못한 것이 남으면 `verify.json`의 `lost`, `resurrected`와 함께 직접 확인한다.
