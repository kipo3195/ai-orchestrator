# 002. User Policy Cache-Aside and Fallback Isolation

Status: Accepted

## Context

User Policy는 Redis에서 우선 조회하고 cache miss 시 DB에서 조회한다. Redis는 runtime cache이고 DB가 Source of Truth다.

정상적인 cache miss와 Redis timeout 또는 connection failure는 운영상 서로 다른 상태다. 하지만 두 경우 모두 DB fallback이 가능해야 한다. Redis 장애로 많은 요청이 동시에 DB fallback으로 전환되면 DB connection pool과 AI Orchestrator의 요청 처리 자원이 고갈될 수 있다.

저장소 상태를 확인할 수 없을 때 OFF를 정상 정책 값으로 저장하면 실제 사용자 설정과 저장소 장애를 구분할 수 없게 된다.

DB 조회가 정상적으로 완료되었지만 User Policy row가 없는 경우도 Redis key가 없는 cache miss와 구분해야 한다. 이 결과를 cache하지 않으면 정책이 등록되지 않은 동일 사용자 요청마다 DB fallback이 반복된다. 반면 DB 장애를 row 없음으로 취급하면 장애 결과가 정상적인 기본 OFF 상태로 고착될 수 있다.

## Decision Drivers

- Redis hit, miss와 장애를 구분해야 한다.
- Redis miss 또는 장애 시 DB fallback을 수행해야 한다.
- Redis 장애가 DB 요청 폭증으로 이어지지 않게 해야 한다.
- Redis와 DB를 모두 사용할 수 없으면 fail-closed 처리해야 한다.
- DB row가 없는 사용자에 대한 반복적인 DB fallback을 제한해야 한다.
- 저장소 장애 결과가 정상적인 OFF 값으로 cache되면 안 된다.
- 1차 구현의 의존성과 복잡성을 최소화해야 한다.

## Considered Options

### Spring Cache Abstraction

`@Cacheable`을 사용하면 cache miss 시 repository 호출을 간단히 연결할 수 있다.

그러나 이 기능은 cache miss와 Redis 장애를 명시적으로 구분하고, Redis read failure 이후의 DB fallback과 cache write failure의 best-effort 처리를 각각 제어해야 한다. Spring Cache abstraction 뒤에 이 차이를 숨기면 Policy의 장애 의미가 불명확해진다.

### Explicit Spring Data Redis Access

Spring Data Redis adapter를 직접 호출하면 Redis GET, DB fallback과 Redis PUT의 순서 및 실패 동작을 명시적으로 제어할 수 있다. 정상적인 OFF, cache miss와 저장소 장애도 서로 다른 결과로 유지할 수 있다.

## Decision

User Policy cache에는 Spring Cache abstraction의 `@Cacheable`을 사용하지 않는다. Infrastructure의 Spring Data Redis adapter를 명시적으로 호출한다.

User Policy 조회는 다음 순서를 따른다.

1. Redis에서 tenant와 user 식별자로 User Policy를 조회한다.
2. Redis hit이면 cached value를 반환한다.
3. Redis miss이면 DB fallback을 수행한다.
4. Redis timeout 또는 connection failure이면 장애를 기록할 수 있는 별도 결과로 구분한 후 DB fallback을 수행한다.
5. DB 조회에 성공하고 row가 있으면 DB 값을 현재 요청의 판단에 사용한다.
6. DB 조회가 정상적으로 완료되었지만 row가 없으면 `NOT_CONFIGURED`로 구분하고 현재 요청은 기본 OFF로 판단한다.
7. DB 조회 성공 후 Redis에는 `ENABLED`, `DISABLED` 또는 `NOT_CONFIGURED`를 TTL과 함께 best effort로 저장한다.
8. Redis 저장 실패는 이미 성공한 현재 요청의 판단을 변경하지 않는다.
9. Redis와 DB를 모두 사용할 수 없으면 `USER_POLICY_UNAVAILABLE`을 반환하고 AI 실행을 차단한다.

## Cache Entry Representation

User Policy cache는 Redis String으로 저장하며 value는 다음 enum 문자열 중 하나다.

```text
ENABLED
DISABLED
NOT_CONFIGURED
```

- `ENABLED`: DB에 명시적인 ON 정책이 있다.
- `DISABLED`: DB에 명시적인 OFF 정책이 있다.
- `NOT_CONFIGURED`: DB 조회는 정상적으로 완료되었지만 해당 tenant와 user의 정책 row가 없다.

`NOT_CONFIGURED`는 저장소 장애가 아니라 정상적인 negative cache 값이다. 정책 평가에서는 기본 OFF로 처리하지만 명시적인 `DISABLED`와 저장 의미를 구분한다.

`USER_POLICY_UNAVAILABLE`은 cache value가 아니다. Redis와 DB를 통해 정책 상태를 확인하지 못한 현재 요청의 실패 결과이며 Redis에 저장하지 않는다.

Infrastructure adapter는 Redis 문자열과 내부 cache value type 사이의 변환을 소유한다. 알 수 없는 문자열이나 역직렬화 실패는 cache miss로 취급하지 않고 Redis read failure로 구분한 후 DB fallback을 수행한다.

## Cache Semantics

- `ENABLED`, `DISABLED`와 `NOT_CONFIGURED`는 정상적인 cache value다.
- Redis key가 없을 때만 cache miss로 처리한다.
- timeout, connection failure와 역직렬화 실패를 cache miss로 위장하지 않는다.
- DB 조회 성공과 DB row 없음을 DB timeout, connection failure 또는 query failure와 구분한다.
- 저장소 장애를 `DISABLED` 또는 `NOT_CONFIGURED`로 변환하지 않는다.
- `USER_POLICY_UNAVAILABLE`을 Redis에 저장하지 않는다.
- `ENABLED`와 `DISABLED`에는 application configuration으로 관리하는 일반 TTL을 적용한다.
- `NOT_CONFIGURED`에는 별도의 negative cache TTL을 적용한다. 정책 생성 후 cache invalidation이 보장되지 않는 경우 일반 TTL보다 짧게 설정하여 기본 OFF 상태가 유지되는 시간을 제한한다.
- Cache hit에서는 TTL을 갱신하지 않는다. DB fallback 성공 후 Redis에 값을 저장할 때 기존 값을 덮어쓰고 TTL을 새로 설정한다.
- Cache key에는 tenant와 user 식별자를 모두 포함한다.

Cache key의 기본 형식은 다음과 같다.

```text
ai:user:{tenantId}:{userId}:policy
```

식별자를 key에 조합하는 규칙은 Infrastructure adapter가 소유한다. Application과 Domain은 Redis key 형식에 의존하지 않는다.

User Policy 변경 주체가 cache invalidation을 지원하면 DB 변경 완료 후 해당 key를 삭제한다. Invalidation이 보장되지 않는 구성에서는 TTL 만료까지 eventual consistency를 허용한다. User Policy 변경 API와 invalidation 전달 방식 자체는 이 결정의 범위에 포함하지 않는다.

## Cache TTL Configuration

일반 cache TTL과 negative cache TTL은 application configuration에서 별도로 관리한다.

```properties
policy.user-cache-ttl=5m
policy.user-negative-cache-ttl=30s
policy.user-fallback-max-concurrency=4
spring.data.redis.timeout=500ms
spring.jdbc.template.query-timeout=2s
```

위 값은 현재 application 기본값이며 배포 환경에서 재정의할 수 있다. 실제 운영 값은 정책 변경 반영 요구 시간, 요청량, 상위 요청의 latency budget과 DB fallback 허용량을 기준으로 결정한다. TTL과 timeout은 양수여야 하고 fallback 동시 실행 제한은 1 이상이어야 한다.

## Fallback Isolation

DB fallback에는 별도의 동시 실행 제한을 적용한다. Connection pool에서 대기하게 하기 전에 fallback 진입 요청 수를 제한하여 Redis 장애가 전체 DB pool 고갈로 확산되는 것을 방지한다.

1차 구현은 추가 resilience library 없이 process-local semaphore를 사용한다. 대기열을 만들지 않고 즉시 획득을 시도한다.

- Semaphore 획득 성공: DB fallback 수행
- Semaphore 획득 실패: DB를 호출하지 않고 `USER_POLICY_UNAVAILABLE` 반환
- DB 조회 완료 또는 실패: `finally` 경계에서 permit 반환

Permit은 DB fallback 조회 범위만 보호한다. DB 조회가 끝나면 Redis cache write 전에 permit을 반환하여 Redis write 지연이나 timeout이 DB fallback 동시 실행 한도를 점유하지 않게 한다. Redis write는 permit 반환 후 현재 요청에서 best effort로 수행하므로 write 지연은 요청 latency에는 포함될 수 있지만 DB fallback permit을 점유하지 않는다.

각 AI Orchestrator instance가 독립된 semaphore를 가지므로 전체 DB fallback 상한은 instance 수에 비례한다. 운영 설정은 instance 수와 DB connection pool 용량을 함께 고려한다.

## Timeout

- Redis read에는 짧고 유한한 timeout을 적용한다.
- DB fallback에는 유한한 query timeout을 적용한다.
- Redis timeout과 DB timeout의 합이 상위 요청의 latency budget을 초과하지 않도록 설정한다.
- DB query timeout의 기술적 적용 방식은 [001. Policy Persistence Access](001-policy-persistence-access.md)를 따른다.

## Failure Matrix

| Redis result | DB fallback result | Redis write | Policy result |
| --- | --- | --- | --- |
| Hit: `ENABLED` | 실행하지 않음 | 실행하지 않음 | enabled |
| Hit: `DISABLED` | 실행하지 않음 | 실행하지 않음 | disabled |
| Hit: `NOT_CONFIGURED` | 실행하지 않음 | 실행하지 않음 | 기본 OFF |
| Miss 또는 장애 | row 있음: enabled | `ENABLED` best effort | enabled |
| Miss 또는 장애 | row 있음: disabled | `DISABLED` best effort | disabled |
| Miss 또는 장애 | 정상 조회: row 없음 | `NOT_CONFIGURED` best effort | 기본 OFF |
| Miss 또는 장애 | timeout 또는 실패 | 저장하지 않음 | `USER_POLICY_UNAVAILABLE` |
| Miss 또는 장애 | 동시 실행 제한 거부 | 저장하지 않음 | `USER_POLICY_UNAVAILABLE` |

## Retry and Circuit Breaker

1차 Policy 구현에는 다음을 도입하지 않는다.

- Redis retry
- DB fallback retry
- Redis circuit breaker
- DB circuit breaker

Redis retry는 DB fallback 시작을 늦추고 요청 latency를 증가시킬 수 있다. DB fallback retry는 장애 중인 DB에 추가 부하를 줄 수 있다. 1차 구현에서는 timeout, DB connection pool과 fallback concurrency limit으로 장애를 격리한다.

Circuit breaker와 retry가 필요한 Omni AI 호출의 resilience 정책은 별도 결정으로 다룬다.

## Consequences

### Positive

- Redis miss와 장애를 구분할 수 있다.
- Redis 장애가 곧바로 User Policy 확인 실패로 이어지지 않는다.
- Redis 장애 시 DB fallback 요청 수를 제한할 수 있다.
- 정책이 등록되지 않은 동일 사용자의 반복적인 DB fallback을 negative cache로 줄일 수 있다.
- 명시적인 OFF, 정책 미등록과 저장소 장애를 서로 다른 상태로 유지할 수 있다.
- 저장소 장애가 정상적인 OFF 값으로 cache되지 않는다.
- Circuit breaker 또는 retry library 없이 1차 장애 격리를 구현할 수 있다.

### Negative

- `@Cacheable`보다 명시적인 분기와 오류 처리가 필요하다.
- Cache value가 boolean보다 복잡해지고 negative cache TTL을 별도로 운영해야 한다.
- Invalidation이 없으면 User Policy가 새로 등록된 후 negative cache TTL 동안 기본 OFF 판단이 유지될 수 있다.
- Instance 수와 DB pool을 고려해 concurrency limit을 운영해야 한다.
- Process-local 제한이므로 전체 cluster 단위의 정확한 상한은 제공하지 않는다.
- Redis 장애 중에는 허용 범위 내에서 DB 부하가 증가한다.

## Non-goals

- User Policy 변경 API와 DB 저장
- Redis를 Source of Truth로 사용
- Circuit breaker와 retry
- Omni AI client resilience
- Global / Feature Policy snapshot 갱신
- Policy metric 및 Actuator 설계

## Related

- [001. Policy Persistence Access](001-policy-persistence-access.md)
- [Policy Immutable Snapshot Refresh](../engineering/001-policy-immutable-snapshot-refresh.md)
- [User Policy Cache-Aside Implementation](../engineering/002-user-policy-cache-aside-implementation.md)
