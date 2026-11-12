# 장애 대응 테스트 결과: 20261112-170527-aws-rds

- 측정 시간: 310초, 요청 1347건

## 이벤트

| 경과(초) | 이벤트 | 내용 |
|---|---|---|
| 0.6 | baseline_start | 정상 상태 기록 60초 |
| 60.6 | inject | rds reboot-db-instance --force-failover zelo-prod-postgres |
| 129.7 | db_available | RDS 상태 available |
| 130.2 | after_start | 복구 후 180초 더 기록 |

## 프로브별 결과

| 프로브 | 요청 | 실패 | 성공률 | 실패 구간(초) | 장애 후 복구까지 | 응답 p50 | p95 | 최대 |
|---|---|---|---|---|---|---|---|---|
| read | 311 | 0 | 100.0% | - | - | 48ms | 131ms | 378ms |
| db_read | 301 | 2 | 99.3% | 17~17, 72~82 | 22.6초 | 108ms | 194ms | 431ms |
| login | 101 | 1 | 99.0% | 72~82 | 23.6초 | 147ms | 225ms | 468ms |
| reissue | 104 | 3 | 97.1% | 6~6, 84~84, 129~129 | 71.4초 | 48ms | 133ms | 376ms |
| addr_create | 264 | 13 | 95.1% | 69~127 | 68.4초 | 61ms | 143ms | 1072ms |
| addr_delete | 266 | 15 | 94.4% | 69~128 | 68.4초 | 58ms | 136ms | 397ms |

## 실패 응답

- db_read: status 0 TimeoutError, status 0 URLError
- login: status 0 TimeoutError
- reissue: status 0 URLError, status 401
- addr_create: status 0 TimeoutError, status 409
- addr_delete: status 409

## 쓰기 유실 확인

- 성공 응답(201)을 받은 배송지 791건, 삭제 성공(204) 251건
- **유실(성공 응답을 받았는데 서버에 없음): 0건** 
- 삭제 성공을 받았는데 남아 있음: 0건 
- 재발급이 401로 끝난 횟수(리프레시 토큰이 서버에서 사라진 횟수): 2
