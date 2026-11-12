# 장애 대응 테스트 결과: 20261112-141352-aws-rds

- 측정 시간: 313초, 요청 1072건

## 이벤트

| 경과(초) | 이벤트 | 내용 |
|---|---|---|
| 0.9 | baseline_start | 정상 상태 기록 60초 |
| 61.0 | inject | rds reboot-db-instance --force-failover zelo-prod-postgres |
| 131.0 | db_available | RDS 상태 available |
| 131.6 | after_start | 복구 후 180초 더 기록 |

## 프로브별 결과

| 프로브 | 요청 | 실패 | 성공률 | 실패 구간(초) | 장애 후 복구까지 | 응답 p50 | p95 | 최대 |
|---|---|---|---|---|---|---|---|---|
| read | 312 | 0 | 100.0% | - | - | 46ms | 130ms | 303ms |
| db_read | 302 | 1 | 99.7% | 68~78 | 19.0초 | 108ms | 197ms | 990ms |
| login | 101 | 1 | 99.0% | 69~79 | 20.2초 | 146ms | 225ms | 383ms |
| reissue | 104 | 1 | 99.0% | 81~81 | 23.1초 | 46ms | 130ms | 158ms |
| addr_create | 128 | 62 | 51.6% | 66~313 | - | 66ms | 153ms | 171ms |
| addr_delete | 125 | 60 | 52.0% | 65~311 | - | 66ms | 148ms | 178ms |

## 실패 응답

- db_read: status 0 TimeoutError
- login: status 0 TimeoutError
- reissue: status 401
- addr_create: status 409
- addr_delete: status 0 TimeoutError, status 409
