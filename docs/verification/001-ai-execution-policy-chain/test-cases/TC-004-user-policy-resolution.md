# TC-004. User Policy resolution

| 항목 | 값 |
|---|---|
| Verification | [AI Execution Policy Chain Verification](../README.md) |
| Test Level | Unit / Integration |

Required 여부, 자동화 상태와 `Last Verification Execution` 기준 실행 결과는 [상위 Verification 문서](../README.md)에서 관리한다. 이 문서의 `Last Execution`은 해당 TC의 최근 단독 또는 전체 실행을 기록하며, 단독 실행만으로 상위 Verification의 상태를 변경하지 않는다.

## Verification Purpose

전체 User Policy가 Redis cache-aside와 제한된 DB fallback으로 해석되고, 명시적 ON/OFF, 미설정과 저장소 unavailable을 서로 구분하면서 실행 정책에는 fail-closed로 반영되는지 검증한다.

## Requirement Traceability

| 구분 | 참조 | 관련 요구사항 |
|---|---|---|
| Engineering | [User Policy Cache-Aside Implementation — Resolution Flow](../../../engineering/002-user-policy-cache-aside-implementation.md#resolution-flow) | Redis 조회, DB fallback, 상태 변환과 best-effort cache write 순서를 정의한다. |
| Decision | [User Policy Cache-Aside and Fallback Isolation](../../../decisions/002-user-policy-cache-aside-and-fallback-isolation.md) | Miss/장애 구분, negative cache와 semaphore 격리를 정의한다. |
| Implementation | [CacheAsideUserAiPolicyProvider](../../../../src/main/java/com/ucware/ai_orchestrator/policy/application/CacheAsideUserAiPolicyProvider.java) | Cache hit, DB fallback과 unavailable 결과를 조정한다. |
| Implementation | [RedisUserAiPolicyCache](../../../../src/main/java/com/ucware/ai_orchestrator/policy/infrastructure/redis/RedisUserAiPolicyCache.java) / [JdbcUserAiPolicyRepository](../../../../src/main/java/com/ucware/ai_orchestrator/policy/infrastructure/persistence/JdbcUserAiPolicyRepository.java) | Redis key/value/TTL과 User 단건 SQL mapping을 구현한다. |

## Risk Addressed

- Redis miss와 장애가 구분되지 않아 fallback이나 운영 진단이 왜곡될 수 있다.
- User row 없음이 매 요청 DB 조회를 유발하거나 DB 장애가 정상 OFF로 cache될 수 있다.
- Redis 장애 시 DB fallback이 무제한으로 증가할 수 있다.
- Cache write 지연이 DB fallback permit을 점유할 수 있다.
- 잘못된 Redis value가 정상 miss로 취급될 수 있다.

## Test Level and Boundary

- 테스트 수준: Application/Redis Adapter Unit 및 H2 JDBC Integration Slice
- 검증 대상: `CacheAsideUserAiPolicyProvider`, `RedisUserAiPolicyCache`, `JdbcUserAiPolicyRepository`, User Domain model
- 실제 사용 컴포넌트: provider 흐름, semaphore, H2 schema와 `JdbcClient`
- Test Double: Redis 및 repository 결과를 제어하는 Mock, Redis key/value 호출 관찰용 `StringRedisTemplate` Mock
- 검증 제외: 실제 Redis server, 운영 DB driver, cluster 전체 fallback 상한

### Rationale

Cache-aside 분기와 동시 실행 제한은 협력 객체 호출 순서를 제어하는 단위 테스트가 적합하고, User SQL mapping은 H2 slice로 실제 schema와 함께 검증한다.

## Preconditions — Given

1. Redis hit, miss, read failure와 write failure 경우가 있다.
2. DB에는 ON/OFF row, row 없음 또는 query failure가 있다.
3. Fallback semaphore permit이 있거나 모두 점유된 상태가 있다.

## Test Data

| 항목 | 값 | 선택 이유 |
|---|---|---|
| Cache key | `ai:user:tenant-1:user-1:policy` | 실제 tenant/user key 계약 |
| Cache values | `ENABLED`, `DISABLED`, `NOT_CONFIGURED`, `UNKNOWN` | 정상 상태와 잘못된 serialization 경계 |
| 일반 TTL | `5m` | 설정된 ON/OFF cache TTL |
| Negative TTL | `30s` | 미설정 사용자 반영 지연을 제한하는 TTL |
| Fallback permits | 가용 또는 0 | 정상 fallback과 과부하 차단 경계 |

## Procedure — When

1. Cache hit 상태에서 User Policy를 resolve한다.
2. Cache miss 또는 read failure 후 DB fallback을 수행한다.
3. DB row 없음, failure와 fallback permit 거절을 각각 발생시킨다.
4. DB 성공 후 Redis write failure 또는 지연을 발생시킨다.
5. Redis adapter의 read/write와 JDBC 단건 조회를 실행한다.

## Expected Results — Then

1. Cache hit은 repository를 호출하지 않고 저장된 상태를 반환한다.
2. Miss 또는 read failure는 permit 범위에서 DB fallback을 수행한다.
3. DB row 없음은 `NOT_CONFIGURED`로 negative cache한다.
4. DB failure와 permit 거절은 `Unavailable`이며 cache하지 않는다.
5. Cache write 실패는 성공한 DB 결과를 바꾸지 않는다.
6. Fallback permit은 DB 조회 완료 후 cache write 전에 반환된다.
7. Redis key/value/TTL과 JDBC tenant/user 조회 결과가 계약과 일치한다.
8. 알 수 없는 cache value는 정상 miss가 아닌 read failure로 처리된다.

## Automated Test Mapping

| 테스트 클래스 | 테스트 메서드 | 검증하는 기대 결과 |
|---|---|---|
| [CacheAsideUserAiPolicyProviderTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/application/CacheAsideUserAiPolicyProviderTests.java) | 전체 테스트 | Then 1~6 |
| [RedisUserAiPolicyCacheTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/redis/RedisUserAiPolicyCacheTests.java) | 전체 테스트 | Then 7, 8 |
| [JdbcUserAiPolicyRepositoryTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/persistence/JdbcUserAiPolicyRepositoryTests.java) | 전체 테스트 | Then 7의 JDBC 경계 |
| [UserAiPolicyTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/domain/UserAiPolicyTests.java) | 전체 테스트 | User 식별자 생성 불변식 |

## Execution Command

```sh
./gradlew test \
  --tests 'com.ucware.ai_orchestrator.policy.application.CacheAsideUserAiPolicyProviderTests' \
  --tests 'com.ucware.ai_orchestrator.policy.infrastructure.redis.RedisUserAiPolicyCacheTests' \
  --tests 'com.ucware.ai_orchestrator.policy.infrastructure.persistence.JdbcUserAiPolicyRepositoryTests' \
  --tests 'com.ucware.ai_orchestrator.policy.domain.UserAiPolicyTests' \
  --rerun-tasks
```

## Last Execution

| 항목 | 값 |
|---|---|
| Executed At | 2026-10-14 17:45 KST |
| Commit | `ac204af` |
| Working Tree | Clean |
| Environment | Java 17.0.19, Gradle 9.7.1 |
| Command | 상위 Verification Tests 명령 |
| Execution Scope | Verification |
| Parent Execution / Evidence | [2026-10-14 verification execution](../evidence/2026-10-14-ac204af.md) |
| Result | Passed |
| Passed / Failed / Skipped | 17 / 0 / 0 |

## Cleanup / Postconditions

- `@JdbcTest` transaction rollback로 H2 User row를 정리한다.
- Mock Redis, repository와 semaphore는 테스트 종료 후 폐기된다.

## Limitations

- 실제 Redis server의 timeout, connection failure와 serialization은 검증하지 않는다.
- Semaphore는 process-local이며 cluster 전체 fallback 상한을 보장하지 않는다.
