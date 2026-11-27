# AI 실행 정책

## 평가 흐름

`AiExecutionPolicyService.evaluateFeature`는 Global → Feature를 평가한다. `evaluateFeatureForUser`는 그 결과가 허용된 경우 전체 User Policy를 추가로 평가한다. 사용자별 기능 정책은 구현하지 않았다.

| 조건 | 결과·사유 |
|---|---|
| snapshot 미초기화 | 차단, `GLOBAL_POLICY_UNAVAILABLE` |
| Global row 누락/OFF | 차단, `GLOBAL_AI_DISABLED` |
| Feature row 누락/OFF | 차단, `FEATURE_AI_DISABLED` |
| User 조회 unavailable | 차단, `USER_POLICY_UNAVAILABLE` |
| User row 없음 | 차단, `USER_POLICY_NOT_CONFIGURED` |
| User OFF | 차단, `USER_AI_DISABLED` |
| 필요한 정책 모두 ON | 허용 |

상위 정책에서 거절하면 하위 User 저장소를 호출하지 않는다. 모든 key는 tenant 범위를 포함한다.

## Global / Feature snapshot

`JdbcPolicySnapshotRepository`가 두 정책 집합을 하나의 read-only transaction에서 읽는다. identifier, feature key, 중복과 null을 검증하고 `AiPolicySnapshot`을 생성한다. collection을 복사한 불변 객체를 `InMemoryAiPolicyStore`의 `AtomicReference`로 통째로 교체한다. 요청은 동일 snapshot에서 Global/Feature를 평가하며 부분 갱신이나 기존 Map 변경을 하지 않는다.

`PolicySnapshotRefresher`는 fixed delay로 갱신한다. 최초 성공 전에는 fail-closed다. 이미 정상 snapshot이 있는 상태의 refresh 실패는 기존 값을 유지한다. 이는 장애 시 모든 요청을 즉시 차단하는 정책이 아니며 snapshot의 최대 유효기간도 현재 없다.

## User cache-aside

```text
Redis GET → hit면 반환
  → miss/read failure → semaphore.tryAcquire
      → 거부면 unavailable
      → DB 조회 성공: ENABLED / DISABLED / NOT_CONFIGURED
      → DB 실패: unavailable
  → finally permit 반환 → 정상 DB 결과만 Redis SET best effort
```

`NOT_CONFIGURED`는 정상적인 negative cache이며 unavailable과 다르다. 저장소 오류를 OFF/미설정 값으로 캐시하지 않는다. Redis 쓰기 실패는 현재 DB 결과를 바꾸지 않는다. permit은 DB 조회가 끝나면 반환하므로 Redis 쓰기 지연이 DB fallback 한도를 점유하지 않는다.

key는 `ai:user:{tenantId}:{userId}:policy`, value는 enum 문자열이다. cache hit는 TTL을 연장하지 않는다. 이벤트 기반 invalidation과 동일 key single-flight는 없다. semaphore 상한은 인스턴스 수에 비례한다.

## 기능 연동

Conversation Start는 예약 실행 직전에 정책을 평가하고 조회 전후로 현재 세션·취소·교체 여부를 재확인한다. Redis/DB I/O는 서비스의 synchronized 구간 밖에서 수행한다.

Returned Message는 lifecycle 생성 전과 YES로 AI 실행을 선점하기 직전에 재평가한다. Policy unavailable은 재시도 가능한 오류이며, 명시적 거절과 구분한다. Permission 판단은 별도 checker를 사용하지만 현재 기본 checker는 허용이다.

## 기본 설정과 검증

| 설정 | 기본값 |
|---|---|
| `policy.snapshot-refresh-interval` | 5s |
| `policy.user-cache-ttl` | 5m |
| `policy.user-negative-cache-ttl` | 30s |
| `policy.user-fallback-max-concurrency` | 4 |
| `spring.data.redis.timeout` | 500ms |
| `spring.jdbc.template.query-timeout` | 2s |

설정 변경 시 정책 반영 지연, 인스턴스 수, DB pool과 상위 요청 timeout을 함께 고려한다. 현재 retry/circuit breaker 라이브러리는 도입하지 않았다.

결정 배경은 [ADR 001](../decisions/001-policy-persistence-access.md), [ADR 002](../decisions/002-user-policy-cache-aside-and-fallback-isolation.md)에 보존한다. 
