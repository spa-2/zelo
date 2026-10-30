# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
docker compose up -d --wait           # start local Postgres 16 (db: zelotest, port 5432) and Redis 7 (port 6379), waits until both are healthy
export OPENAI_API_KEY=...             # required: application.yaml reads ${OPENAI_API_KEY}, startup fails without it
export JWT_SECRET=...                 # required: application.yaml reads ${JWT_SECRET} (Base64-encoded; decodes to at least 32 bytes), startup fails without it (tests use a dummy key from application-test.yaml)
export SOCIAL_MODE=fake               # optional: application.yaml reads ${SOCIAL_MODE:fake}. fake accepts any "fake-*" code as a login, real calls Google/Kakao (never use fake in production)
export GOOGLE_CLIENT_ID=...           # only for SOCIAL_MODE=real: application.yaml reads ${GOOGLE_CLIENT_ID} (Google OAuth client ID)
export GOOGLE_CLIENT_SECRET=...       # only for SOCIAL_MODE=real: application.yaml reads ${GOOGLE_CLIENT_SECRET}
export KAKAO_CLIENT_ID=...            # only for SOCIAL_MODE=real: application.yaml reads ${KAKAO_CLIENT_ID} (Kakao REST API key)
export KAKAO_CLIENT_SECRET=...        # only for SOCIAL_MODE=real: application.yaml reads ${KAKAO_CLIENT_SECRET}
export LLM_MODE=fake                  # optional: application.yaml reads ${LLM_MODE:fake}. fake returns canned LLM responses, real calls OpenAI (OPENAI_API_KEY is still read either way)
export MAIL_MODE=fake                 # optional: application.yaml reads ${MAIL_MODE:fake}. fake logs the mail instead of sending it, real sends through AWS SES
export MAIL_FROM=...                  # only for MAIL_MODE=real: SES verified sender address (zelo.mail.from), must match the IAM ses:FromAddress condition
./gradlew bootRun                     # run the app
./gradlew build                       # compile + test (what CI runs, followed by `sonar`)
./gradlew test                        # all tests (JUnit Platform)
./gradlew test --tests 'com.server.zelo.ZeloApplicationTests'            # single class
./gradlew test --tests 'com.server.zelo.SomeTest.someMethod'                 # single method
```

No lint/format plugin is configured. Note that `ZeloApplicationTests` loads the full Spring context, so it needs a reachable Postgres, Redis (Redisson connects at startup) and `OPENAI_API_KEY`. `JWT_SECRET` is not needed for tests that use the `test` profile (it sets a dummy key), and neither are the social login keys (`zelo.social.mode: fake`); they are only read when the app runs with `SOCIAL_MODE=real`. The other unit tests need none of them.

## Configuration notes

- `application.yaml` hardcodes the local DB (matches `docker-compose.yml`) and uses `ddl-auto: update` (no migration tool).
- JPA `open-in-view` is disabled, so lazy associations must be loaded inside the transaction.

## CI and review

- CI runs `./gradlew build sonar` on pushes to `main` and PRs (needs `SONAR_TOKEN`).
- CodeRabbit reviews PRs in Korean (`.coderabbit.yaml`): missing `@Valid`, business logic in controllers, wrong `@Transactional` boundaries, N+1, race conditions, common error-code violations, and widened `permitAll`.

## Testing
- 테스트 메서드명은 한글 허용: `포인트가_부족하면_예외가_발생한다()`

## Git conventions
- 브랜치: `feat/#이슈번호-설명`, `fix/#12-login-error`. main 직접 커밋 금지
- 커밋: `type: 제목` (feat, fix, refactor, test, docs, chore), 제목은 한글 50자 이내
- PR 제목은 커밋 형식과 동일, 본문에 `Closes #이슈번호`, 변경 요약, 테스트 방법 포함
- 하나의 커밋에는 하나의 목적만 담는다

## Don't
- `application.yaml`의 DB 정보나 API 키를 수정하거나 커밋하지 않는다
- 사용자 확인 없이 `git push`, `git reset --hard`를 실행하지 않는다
- 요청받지 않은 파일을 리팩터링하지 않는다

## Code conventions

### 패키지 · 네이밍
- 공통 기능은 `common`, 비즈니스 코드는 도메인 패키지(`user`, `order`, `payment` ...)에 둔다.
- 클래스명은 `도메인 + 역할`: `OrderFacade`, `OrderCustomRepository`, `OrderCustomRepositoryImpl`.
- Command 클래스는 `도메인 + CommandService`로 쓴다: `UserCommandService`, `SocialCommandService`.
- 어노테이션은 **짧은 것 → 긴 것** 순서로 쓴다. (`@Slf4j` → `@Service` → `@RequiredArgsConstructor`)
- 필드명은 타입명과 맞추고, 선언도 **짧은 것 → 긴 것** 순서로 쓴다.
### 계층 책임 (호출 흐름: Controller → Facade → Command → Service → Repository)
- Facade와 Command는 필요한 경우에만 둔다. 필요 없는 계층은 건너뛰고 바로 다음 계층을 호출한다. 아래 순서로 판단한다.
    1. 락이 필요한가? → **Facade**를 둔다.
    2. 자기 도메인의 Service 하나(그 Service의 Repository)로 끝나는가? → Controller가 **Service**를 바로 호출한다.
    3. 아래 중 하나라도 해당하는가? → **Command**를 둔다.
        - 다른 도메인의 Service가 필요하다.
        - 토큰 발급, 외부 API처럼 도메인 밖의 협력 객체를 순서대로 엮어야 한다.
        - 여러 Service 호출을 하나의 트랜잭션으로 묶어야 한다.
    - 예: 락이 필요 없고 조율만 필요하면 Controller → Command, 락만 필요하면 Controller → Facade → Service.
- **Facade**: 공통 락 클래스로 락만 건다. `@Transactional`을 붙이지 않는다. (락은 트랜잭션 커밋 후에 풀려야 하기 때문) 락이 필요 없으면 만들지 않는다. 호출만 넘기는 메서드를 두지 않는다.
- **Command**: 위 3번에 해당하는 작업에서 호출 순서를 정하고, 전체 작업의 트랜잭션 경계를 담당한다. 해당하지 않으면 만들지 않는다.
- **Service**: 자기 도메인 Repository만 주입받는다. 다른 도메인 Service를 주입하지 않는다. 그런 조율이 필요하면 Command를 만든다.
### 트랜잭션
- Command가 있으면 Command가 트랜잭션 경계를 잡고, 그 안의 Service는 같은 트랜잭션에 참여한다. (안쪽 Service의 `readOnly`는 무시된다.)
- Command 없이 Service를 바로 호출하는 경우, JPA를 쓰는 Service 클래스에 `@Transactional`을 붙이고 조회 메서드에만 `@Transactional(readOnly = true)`를 붙인다. (조회 메서드에 `readOnly`를 빠뜨려도 동작은 정상이지만, 반대로 쓰기 메서드에 `readOnly`를 붙이면 변경이 저장되지 않기 때문)
- Redis처럼 JPA를 쓰지 않는 Service에는 붙이지 않는다.
- `open-in-view`가 꺼져 있으므로 지연 로딩이 필요한 조회는 트랜잭션 안에서 처리한다.
### 락
- 분산락은 `common.lock.LockDistributeService`(Redisson)로만 건다. `RedissonClient`를 직접 쓰지 않는다.
- 사용법: `lockDistributeService.execute(LockKey.xxx(...), () -> command.xxx(...))`. 반환값이 있으면 `Supplier`, 없으면 `Runnable`을 넘긴다.
- 락 키는 `common.lock.LockKey`에 정적 메서드로 모아서 관리한다. Facade에서 키 문자열을 직접 만들지 않는다.
- 3초 안에 락을 얻지 못하면 `ErrorCode.TIMEOUT_EXCEPTION`(409)을 던진다. 락 대기 중 인터럽트도 같은 예외다.
- 락은 Facade에서 Command 호출 전체를 감싸서 건다. 그래야 Command의 트랜잭션이 커밋된 뒤에 락이 풀린다.
- Redisson이 Redis에 접속하므로 로컬과 CI에 Redis(기본 localhost:6379)가 필요하다.
### Repository
- 인터페이스에 `@Repository`를 명시한다.
- 조회 방식은 다음 순서로 고른다: JPA 기본 메서드 → 연관관계 조회가 필요하면 `@EntityGraph` → JPQL → 조건이 복잡하면 QueryDSL.
### 메서드 네이밍
- `getXxx()`: 대상이 없으면 예외를 던진다. `findXxx()`: 결과가 없을 수 있고 `Optional`이나 빈 결과를 반환한다.
- 생성/수정/삭제는 `create`/`update`/`delete`를 쓴다.
- 상태 전이는 도메인 언어를 쓴다: `approve()`, `cancel()`, `complete()`.
- `validateXxx()`는 실패하면 예외를 던지고, `isXxx()`는 boolean을 반환한다.
### Entity
- `@Setter`와 Builder를 쓰지 않는다. 생성은 정적 팩토리(`OrderItem.of(...)`)로, 상태 변경은 의도가 드러나는 메서드로 한다.
- 모든 엔티티는 `BaseEntity`를 상속한다.
- `@ManyToOne`에는 `fetch = FetchType.LAZY`를 명시한다.
- 상태 전이 검증은 엔티티 내부에서 한다.
- `@Column`에 `nullable`, `length`를 명시한다.
### DTO
- `record`로 작성한다. 컨트롤러는 엔티티가 아닌 DTO만 반환한다.
- 이름은 `동작 + 도메인 + 접미사` 형식이다: `CreateOrderRequest`, `GetOrderResponse`, `SearchOrderCondition`(page, size, 검색 조건).
- 정적 팩토리는 값을 그대로 받으면 `of`, 다른 타입을 변환하면 `from`을 쓴다. 엔티티 → DTO 변환은 DTO 안에 둔다.
- 검증 규칙
    - 컨트롤러 파라미터에 `@Valid`를 붙인다.
    - 검증 어노테이션에 `message` 속성을 쓰지 않는다.
    - 숫자 필드는 `Integer`, `Long` 같은 래퍼 타입을 쓰고 `@NotNull`을 붙인다. 범위는 `@Min`/`@Max`로 지정한다.
    - 문자열 필드는 `@NotBlank`를 붙인다. 필요하면 `@Size`나 `@Pattern`을 추가한다.
### 공통 응답
- 일반 응답은 `ResponseEntity<ApiResponse<T>>`를 반환한다.
- 페이지 응답은 `ResponseEntity<ApiResponse<PageResponse<T>>>`를 반환한다. `Page`를 그대로 캐시하거나 반환하지 않고 `PageResponse`로 변환한다.
### 기타
- 주석은 "왜 이렇게 했는가"가 필요할 때만 `//`로 쓴다.
- JWT 키, DB 비밀번호 같은 시크릿은 환경변수나 별도 파일로 분리하고, 그 파일은 `.gitignore`에 등록한다.
### 테스트
- `if ... throw` 분기가 있는 기능에는 단위 테스트를 작성한다.
- JPQL이나 QueryDSL 같은 커스텀 쿼리에는 Repository 테스트를 작성한다.
- 단위 테스트는 `@ExtendWith(MockitoExtension.class)`를 쓰고, 스텁은 BDDMockito `given(...).willReturn(...)`, 검증은 AssertJ `assertThat(...)`으로 한다. 본문은 `// given // when // then` 주석으로 구분한다.
### 미정 (임의로 만들지 말고 먼저 물어볼 것)
- 현재 없음

