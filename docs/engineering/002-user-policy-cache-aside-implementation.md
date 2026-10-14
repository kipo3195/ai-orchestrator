# 002. User Policy Cache-Aside Implementation

Status: Draft

## Purpose

Tenant와 user 식별자를 사용해 User Policy를 조회하고, Redis cache-aside와 격리된 DB fallback으로 정책 상태를 결정하는 현재 구현 구조를 기록한다. 이 문서는 [002. User Policy Cache-Aside and Fallback Isolation](../decisions/002-user-policy-cache-aside-and-fallback-isolation.md)의 결정을 코드에 적용하는 경계와 기능별 사용 방식을 설명한다.

## Scope

- User Policy 도메인 상태와 조회 결과
- Redis cache 조회와 저장
- JDBC 기반 단건 조회
- DB fallback 동시 실행 제한
- `AiExecutionPolicyService.evaluateFeatureForUser(...)`의 전체 사용자 정책 평가
- Conversation Start 실행 직전 User Policy 확인
- 설정과 테스트 경계

## Non-scope

- User Policy 변경 API와 DB 저장
- 정책 변경 이벤트와 Redis key invalidation 전달 방식
- Cluster 전체에 적용되는 fallback 동시 실행 상한
- Retry와 circuit breaker
- 사용자별 기능 정책과 `UserFeaturePolicyProvider`

사용자 식별자가 없는 AI 기능이 존재할 수 있으므로 정책 서비스는 `evaluateFeature(...)`와 `evaluateFeatureForUser(...)`를 제공한다. 전자는 Global → Feature를 평가하고, 후자는 같은 평가가 허용된 경우에만 사용자의 전체 AI 사용 정책을 추가로 평가한다.

현재 User Policy는 사용자의 전체 AI 사용 여부만 표현한다. 사용자별 기능 정책을 별도로 설정하고 관리해야 하는 요구가 실제로 생기면 `UserFeaturePolicyProvider`와 `(tenantId, userId, feature)` 저장·캐시 모델을 별도 확장으로 도입한다.

## Component Boundary

```text
policy/
├── application
│   ├── AiExecutionPolicyService
│   ├── CacheAsideUserAiPolicyProvider
│   └── port
│       ├── UserAiPolicyCache
│       └── UserAiPolicyRepository
├── domain
│   ├── UserAiPolicy
│   ├── UserAiPolicyStatus
│   ├── UserAiPolicyResolution
│   └── UserAiPolicyProvider
└── infrastructure
    ├── config
    │   ├── PolicyProperties
    │   └── UserAiPolicyConfig
    ├── persistence
    │   └── JdbcUserAiPolicyRepository
    └── redis
        └── RedisUserAiPolicyCache
```

- `UserAiPolicy`는 DB에 존재하는 tenant별 사용자 정책 row를 표현한다.
- `UserAiPolicyStatus`는 정상적으로 확인된 `ENABLED`, `DISABLED`, `NOT_CONFIGURED` 상태를 표현한다.
- `UserAiPolicyResolution`은 정상 상태인 `Resolved`와 저장소를 통해 확인하지 못한 `Unavailable`을 구분한다.
- `UserAiPolicyRepository`는 DB 단건 조회를 위한 Application port다.
- `UserAiPolicyCache`는 Redis 조회와 TTL 저장을 위한 Application port다.
- `CacheAsideUserAiPolicyProvider`는 cache-aside, DB fallback, semaphore와 best-effort cache write를 조정한다.
- `JdbcUserAiPolicyRepository`와 `RedisUserAiPolicyCache`는 Infrastructure adapter다.
- `AiExecutionPolicyService`는 조회 결과를 AI 실행 허용 또는 거절 결정으로 변환한다.

Application은 Redis key, `StringRedisTemplate`, `JdbcClient`와 DB schema에 의존하지 않는다.

## Domain and Result Semantics

| 상태 또는 결과 | 의미 | Cache 가능 여부 | 정책 판단 |
| --- | --- | --- | --- |
| `ENABLED` | DB에 명시적인 ON row가 있음 | 가능 | 허용 |
| `DISABLED` | DB에 명시적인 OFF row가 있음 | 가능 | 거절 |
| `NOT_CONFIGURED` | DB 조회 성공, row 없음 | 가능 | 기본 OFF로 거절 |
| `Unavailable` | DB 실패 또는 fallback 진입 거부 | 불가 | fail-closed 거절 |

`NOT_CONFIGURED`는 정상적인 negative cache 상태다. `Unavailable`은 현재 요청의 저장소 확인 실패이며 Redis에 저장하지 않는다.

## Storage Contracts

### JDBC

현재 JDBC adapter는 다음 의미의 schema를 사용한다.

```text
ai_user_policy
├── tenant_id
├── user_id
└── enabled

primary key: (tenant_id, user_id)
```

`findByTenantIdAndUserId(tenantId, userId)`는 다음 계약을 가진다.

- `Optional.of(UserAiPolicy)`: row 존재
- `Optional.empty()`: 조회 성공, row 없음
- 예외: timeout, connection failure, query failure 또는 잘못된 row

현재 `schema.sql`은 로컬 H2 실행과 테스트를 위한 동일 구조를 제공한다. 운영 Policy DB의 실제 schema와 JDBC driver 호환성은 별도 배포 통합 검증 대상이다.

### Redis

Redis는 String key와 enum 문자열 value를 사용한다.

```text
key   = ai:user:{tenantId}:{userId}:policy
value = ENABLED | DISABLED | NOT_CONFIGURED
```

`UserAiPolicyCache.find(...)`의 `Optional.empty()`는 key가 없는 정상 cache miss만 의미한다. Redis timeout, connection failure와 알 수 없는 value는 예외로 전달되어 cache miss와 구분된다.

`ENABLED`와 `DISABLED`에는 일반 TTL을 적용하고 `NOT_CONFIGURED`에는 별도의 negative cache TTL을 적용한다. Cache hit에서는 TTL을 갱신하지 않는다.

## Resolution Flow

```text
Redis GET
├── Hit
│   └── cached status 반환
└── Miss 또는 read failure
    └── DB fallback permit 즉시 획득 시도
        ├── 획득 실패
        │   └── Unavailable
        └── 획득 성공
            ├── DB row 있음 → ENABLED 또는 DISABLED
            ├── DB row 없음 → NOT_CONFIGURED
            └── DB 실패 → Unavailable
```

DB에서 정상 상태가 결정되면 다음 순서를 따른다.

```text
DB 조회와 상태 변환 완료
→ finally에서 DB fallback permit 반환
→ Redis SET best effort
→ 현재 요청에 DB 결과 반환
```

Redis cache write 실패는 이미 성공한 DB 결과를 변경하지 않는다.

## Fallback Isolation

각 process는 `policy.user-fallback-max-concurrency` 수만큼 permit을 가진 독립적인 `Semaphore`를 사용한다. `tryAcquire()`로 대기 없이 진입을 시도하며 permit이 없으면 DB를 호출하지 않고 `Unavailable`을 반환한다.

Semaphore는 DB fallback 조회만 보호한다. Redis cache write는 permit 반환 후 수행한다. 따라서 Redis write timeout이 DB fallback permit을 점유하지 않지만, 느린 Redis write가 여러 요청에서 동시에 실행될 수 있고 현재 요청 latency에는 영향을 줄 수 있다.

이 제한은 사용자별 single-flight가 아니다. 같은 사용자의 동시 cache miss도 permit 범위 내에서 각각 DB를 조회할 수 있다. Cluster 전체 상한은 instance 수에 비례한다.

## Policy Evaluation

`AiExecutionPolicyService.evaluateFeatureForUser(tenantId, feature, userId)`는 Global과 Feature가 허용된 뒤 전체 User Policy를 다음과 같이 변환한다.

| Resolution | Decision reason | Allowed |
| --- | --- | --- |
| `ENABLED` | `ALLOWED` | true |
| `DISABLED` | `USER_AI_DISABLED` | false |
| `NOT_CONFIGURED` | `USER_POLICY_NOT_CONFIGURED` | false |
| `Unavailable` | `USER_POLICY_UNAVAILABLE` | false |

Tenant와 user 식별자가 `null` 또는 blank이면 provider를 호출하기 전에 입력 오류로 거절한다.

## Conversation Start Integration

Conversation Start는 예약된 AI 호출 직전에 다음 순서로 정책을 평가한다.

```text
현재 execution 확인
→ Global Policy 평가
→ Global 허용 시 Feature Policy 평가
→ Feature 허용 시 전체 User Policy 평가
→ execution을 다시 조회해 취소·교체 여부 확인
→ 세 정책이 모두 허용된 경우에만 EXECUTING 전환
→ AI port 호출
```

User Policy 조회는 Redis와 DB I/O를 포함할 수 있으므로 `ConversationStartService`의 `synchronized (this)` 밖에서 수행한다. 정책 조회 전후의 짧은 동기화 구간에서 execution이 여전히 실행 가능하고 현재 요청인지 확인하여 조회 중 발생한 취소, 재입장 또는 execution 교체를 반영한다.

Global 또는 Feature Policy가 거절되면 User Policy를 조회하지 않는다. User Policy가 없는 AI 기능은 `evaluateFeature(...)`를 사용하고, 사용자의 전체 AI 정책까지 필요한 기능은 `evaluateFeatureForUser(...)`를 사용한다.

## Configuration

현재 application 기본값은 다음과 같다.

```properties
policy.snapshot-refresh-interval=5s
policy.user-cache-ttl=5m
policy.user-negative-cache-ttl=30s
policy.user-fallback-max-concurrency=4

spring.data.redis.timeout=500ms
spring.jdbc.template.query-timeout=2s
```

- `PolicyProperties`는 cache TTL과 fallback 동시 실행 제한을 바인딩하고 양수 여부를 검증한다.
- `spring.data.redis.timeout`은 Redis command timeout을 제한한다.
- `spring.jdbc.template.query-timeout`은 현재 Spring JDBC template에 적용되는 전역 query timeout이다.
- Redis와 DB timeout의 합은 기능별 상위 요청 latency budget 안에 있어야 한다.

## Testing

다음 테스트 수준을 사용한다.

- Domain unit test: 식별자 검증
- Application unit test: cache hit, DB fallback, negative cache, 장애와 semaphore 동작
- Redis adapter unit test: key, value 변환과 TTL 선택
- JDBC slice test: tenant와 user를 사용한 단건 조회와 row 없음
- Policy service unit test: 상태별 허용과 거절 reason
- Conversation Start unit test: AI 호출 직전 정책 차단과 조회 중 execution 재검증

세부 동작은 각 구현과 함께 있는 자동화 테스트에서 관리한다. 별도의 User Policy Verification은 두지 않으며, Feature Policy를 포함한 정책 평가 체인이 완성되면 Global, Feature, User Policy의 평가 순서와 실제 AI 실행 차단을 통합 Verification 범위로 검증한다.

## Known Limitations

- 실제 Redis server를 사용하는 timeout, connection failure와 serialization 통합 테스트가 없다.
- 운영 Policy DB schema와 driver 호환성은 검증하지 않았다.
- 정책 변경 이벤트 기반 cache invalidation은 구현하지 않았다.
- 같은 key의 동시 cache miss를 하나의 DB 조회로 합치지 않는다.
- Redis write는 동기 best effort이므로 실패해도 정책 결과는 유지하지만 현재 요청 latency에는 영향을 줄 수 있다.
- Process-local semaphore는 cluster 전체 fallback 상한을 제공하지 않는다.
- 사용자별 기능 정책은 현재 관리하지 않는다. 해당 정책의 독립적인 관리 필요가 생길 때 `UserFeaturePolicyProvider`를 도입한다.

## Related

- [001. Policy Persistence Access](../decisions/001-policy-persistence-access.md)
- [002. User Policy Cache-Aside and Fallback Isolation](../decisions/002-user-policy-cache-aside-and-fallback-isolation.md)
- [Policy Immutable Snapshot Refresh](001-policy-immutable-snapshot-refresh.md)
