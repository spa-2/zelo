# 장애 대응 테스트 결과: 20261112-165351-aws-rds

- 측정 시간: 310초, 요청 1327건

## 이벤트

| 경과(초) | 이벤트 | 내용 |
|---|---|---|
| 0.1 | baseline_start | 정상 상태 기록 60초 |
| 60.2 | inject | rds reboot-db-instance --force-failover zelo-prod-postgres |
| 130.1 | db_available | RDS 상태 available |
| 130.6 | after_start | 복구 후 180초 더 기록 |

## 프로브별 결과

| 프로브 | 요청 | 실패 | 성공률 | 실패 구간(초) | 장애 후 복구까지 | 응답 p50 | p95 | 최대 |
|---|---|---|---|---|---|---|---|---|
| read | 311 | 0 | 100.0% | - | - | 47ms | 131ms | 267ms |
| db_read | 282 | 2 | 99.3% | 67~88 | 38.1초 | 111ms | 202ms | 9283ms |
| login | 100 | 1 | 99.0% | 69~79 | 21.0초 | 149ms | 232ms | 5418ms |
| reissue | 104 | 0 | 100.0% | - | - | 49ms | 131ms | 169ms |
| addr_create | 264 | 13 | 95.1% | 65~123 | 64.9초 | 65ms | 150ms | 1159ms |
| addr_delete | 266 | 15 | 94.4% | 65~124 | 64.8초 | 60ms | 146ms | 266ms |

## 실패 응답

- db_read: status 0 TimeoutError
- login: status 0 TimeoutError
- addr_create: status 0 TimeoutError, status 409
- addr_delete: status 409

## 쓰기 유실 확인

- 성공 응답(201)을 받은 배송지 791건, 삭제 성공(204) 251건
- **유실(성공 응답을 받았는데 서버에 없음): 0건** 
- 삭제 성공을 받았는데 남아 있음: 0건 
- 재발급이 401로 끝난 횟수(리프레시 토큰이 서버에서 사라진 횟수): 0
