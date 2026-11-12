# 장애 대응 테스트 결과: 20261112-125407-local-rds

- 측정 시간: 96초, 요청 398건

## 이벤트

| 경과(초) | 이벤트 | 내용 |
|---|---|---|
| 0.5 | baseline_start | 정상 상태 기록 20초 |
| 20.6 | inject | docker stop zelotest-postgres |
| 36.0 | restore | docker start zelotest-postgres |
| 36.2 | after_start | 복구 후 60초 더 기록 |

## 프로브별 결과

| 프로브 | 요청 | 실패 | 성공률 | 실패 구간(초) | 장애 후 복구까지 | 응답 p50 | p95 | 최대 |
|---|---|---|---|---|---|---|---|---|
| read | 97 | 0 | 100.0% | - | - | 14ms | 22ms | 26ms |
| db_read | 78 | 1 | 98.7% | 21~31 | 20.7초 | 20ms | 30ms | 9279ms |
| login | 28 | 1 | 96.4% | 21~31 | 20.8초 | 111ms | 127ms | 8376ms |
| reissue | 33 | 0 | 100.0% | - | - | 14ms | 20ms | 24ms |
| addr_create | 80 | 3 | 96.2% | 21~39 | 20.7초 | 26ms | 36ms | 1287ms |
| addr_delete | 82 | 5 | 93.9% | 21~40 | 20.7초 | 20ms | 35ms | 295ms |

## 실패 응답

- db_read: status 0 TimeoutError
- login: status 0 TimeoutError
- addr_create: status 0 TimeoutError, status 409
- addr_delete: status 409

## 쓰기 유실 확인

- 성공 응답(201)을 받은 배송지 202건, 삭제 성공(204) 77건
- **유실(성공 응답을 받았는데 서버에 없음): 0건** 
- 삭제 성공을 받았는데 남아 있음: 0건 
- 재발급이 401로 끝난 횟수(원인 미확인): 0
