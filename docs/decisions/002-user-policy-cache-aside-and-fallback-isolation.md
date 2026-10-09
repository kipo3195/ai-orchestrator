# 002. User Policy Cache-Aside and Fallback Isolation

Status: Accepted

## Context

User Policy는 Redis에서 우선 조회하고 cache miss 시 DB에서 조회한다. Redis는 runtime cache이고 DB가 Source of Truth다.

정상적인 cache miss와 Redis timeout 또는 connection failure는 운영상 서로 다른 상태다. 하지만 두 경우 모두 DB fallback이 가능해야 한다. Redis 장애로 많은 요청이 동시에 DB fallback으로 전환되면 DB connection pool과 AI Orchestrator의 요청 처리 자원이 고갈될 수 있다.

저장소 상태를 확인할 수 없을 때 OFF를 정상 정책 값으로 저장하면 실제 사용자 설정과 저장소 장애를 구분할 수 없게 된다.

## Decision Drivers

- Redis hit, miss와 장애를 구분해야 한다.
- Redis miss 또는 장애 시 DB fallback을 수행해야 한다.
- Redis 장애가 DB 요청 폭증으로 이어지지 않게 해야 한다.
- Redis와 DB를 모두 사용할 수 없으면 fail-closed 처리해야 한다.
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
5. DB 조회에 성공하면 DB 값을 현재 요청의 판단에 사용한다.
6. DB 조회 성공 후 Redis 저장은 TTL을 적용해 best effort로 수행한다.
7. Redis 저장 실패는 이미 성공한 현재 요청의 판단을 변경하지 않는다.
8. Redis와 DB를 모두 사용할 수 없으면 `USER_POLICY_UNAVAILABLE`을 반환하고 AI 실행을 차단한다.

## Cache Semantics

- `true`와 `false`는 모두 정상적인 User Policy 값이다.
- Redis key가 없을 때만 cache miss로 처리한다.
- timeout, connection failure와 역직렬화 실패를 cache miss로 위장하지 않는다.
- 저장소 장애를 `false`로 변환하지 않는다.
- `USER_POLICY_UNAVAILABLE`을 Redis에 저장하지 않는다.
- Redis entry에는 application configuration으로 관리하는 TTL을 적용한다.
- Cache key에는 tenant와 user 식별자를 모두 포함한다.

Cache key의 기본 형식은 다음과 같다.

```text
ai:user:{tenantId}:{userId}:enabled
```

식별자를 key에 조합하는 규칙은 Infrastructure adapter가 소유한다. Application과 Domain은 Redis key 형식에 의존하지 않는다.

## Fallback Isolation

DB fallback에는 별도의 동시 실행 제한을 적용한다. Connection pool에서 대기하게 하기 전에 fallback 진입 요청 수를 제한하여 Redis 장애가 전체 DB pool 고갈로 확산되는 것을 방지한다.

1차 구현은 추가 resilience library 없이 process-local semaphore를 사용한다. 대기열을 만들지 않고 즉시 획득을 시도한다.

- Semaphore 획득 성공: DB fallback 수행
- Semaphore 획득 실패: DB를 호출하지 않고 `USER_POLICY_UNAVAILABLE` 반환
- DB 조회 완료 또는 실패: `finally` 경계에서 permit 반환

각 AI Orchestrator instance가 독립된 semaphore를 가지므로 전체 DB fallback 상한은 instance 수에 비례한다. 운영 설정은 instance 수와 DB connection pool 용량을 함께 고려한다.

## Timeout

- Redis read에는 짧고 유한한 timeout을 적용한다.
- DB fallback에는 유한한 query timeout을 적용한다.
- Redis timeout과 DB timeout의 합이 상위 요청의 latency budget을 초과하지 않도록 설정한다.
- DB query timeout의 기술적 적용 방식은 [001. Policy Persistence Access](001-policy-persistence-access.md)를 따른다.

## Failure Matrix

| Redis result | DB fallback | Policy result |
| --- | --- | --- |
| Hit: enabled | 실행하지 않음 | enabled |
| Hit: disabled | 실행하지 않음 | disabled |
| Miss | 성공 | DB 값 |
| Miss | 실패 | `USER_POLICY_UNAVAILABLE` |
| 장애 | 성공 | DB 값 |
| 장애 | 실패 | `USER_POLICY_UNAVAILABLE` |
| Miss 또는 장애 | 동시 실행 제한 거부 | `USER_POLICY_UNAVAILABLE` |

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
- 저장소 장애가 정상적인 OFF 값으로 cache되지 않는다.
- Circuit breaker 또는 retry library 없이 1차 장애 격리를 구현할 수 있다.

### Negative

- `@Cacheable`보다 명시적인 분기와 오류 처리가 필요하다.
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
