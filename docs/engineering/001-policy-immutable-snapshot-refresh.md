# 001. Policy Immutable Snapshot Refresh

Status: Draft

## Purpose

Global / Feature Policy를 Spring Scheduler로 주기적으로 조회하고, 검증된 immutable snapshot을 요청 처리 경로에 원자적으로 제공하는 구현 방식을 기록한다.

이 문서는 Policy 기능에 한정된 engineering note다. 프로젝트 전체의 scheduled cache 또는 reference data 관리 표준을 정의하지 않는다. 동일한 패턴이 다른 기능에서도 반복될 때 공통 engineering guideline으로 분리할 수 있다.

## Scope

- Spring Scheduler 기반 refresh
- Immutable Policy snapshot 구성
- Snapshot의 원자적 교체
- 애플리케이션 시작 시 fail-closed
- Refresh 실패 시 마지막 정상 snapshot 유지
- Scheduler 실행과 snapshot 테스트 기준

## Non-scope

- Policy DB 접근 기술 선택
- User Policy Redis cache-aside와 DB fallback
- Policy metric과 Actuator 구성
- Circuit breaker와 retry
- Admin 또는 User Policy 변경 API

## Component Boundary

```text
policy/
├── application
│   └── AiExecutionPolicyService
├── domain
│   └── PolicySnapshot
└── infrastructure
    ├── config
    │   └── PolicyProperties
    ├── persistence
    │   └── JdbcPolicySnapshotRepository
    └── scheduling
        └── PolicySnapshotRefresher
```

- `PolicySnapshot`은 Spring, JDBC와 Scheduler API에 의존하지 않는다.
- `PolicySnapshotRefresher`는 Infrastructure에서 Spring scheduling과 repository 호출을 연결한다.
- Application은 현재 snapshot을 제공하는 기술 독립적인 interface를 통해 정책을 평가한다.
- JDBC 구현 세부사항은 [001. Policy Persistence Access](../decisions/001-policy-persistence-access.md)를 따른다.

## Snapshot Model

Tenant별 Global Policy 전체를 하나의 snapshot으로 관리한다. Tenant별 값을 따로 게시하면 같은 시점에 조회된 정책 집합이 부분적으로 노출될 수 있기 때문이다.

Snapshot에는 최소한 다음 상태가 포함된다.

- 최초 정상 refresh 완료 여부
- Tenant별 Global AI 활성화 상태
- 정상 로딩 시각

개념적인 모델은 다음과 같다.

```java
public record PolicySnapshot(
        boolean initialized,
        Map<String, Boolean> tenantGlobalPolicies,
        Instant loadedAt
) {
    public PolicySnapshot {
        tenantGlobalPolicies = Map.copyOf(tenantGlobalPolicies);
    }
}
```

실제 Domain 이름은 구현 모델에 맞춘다. 중요한 조건은 snapshot 생성 후 내부 상태가 변경되지 않는다는 것이다.

## Atomic Publication

현재 snapshot은 `AtomicReference<PolicySnapshot>`으로 보관한다.

Refresh는 다음 순서로 실행한다.

```text
DB에서 tenant별 Global Policy 전체 조회
→ 조회 결과 검증
→ 새로운 immutable snapshot 생성
→ AtomicReference를 한 번에 교체
```

다음 방식은 사용하지 않는다.

- 현재 snapshot의 `Map`을 제자리에서 수정
- 일부 tenant 상태를 먼저 교체하고 나머지 tenant 상태를 나중에 교체
- 조회된 일부 Policy만 기존 snapshot에 병합
- 검증 실패 결과로 현재 snapshot을 덮어쓰기

하나의 AI 요청은 평가 시작 시 현재 snapshot을 한 번만 읽고 자신의 `tenantId`로 Global Policy를 판단한다. 평가 도중 현재 reference를 반복해서 읽지 않는다. Tenant row가 없으면 OFF로 판단한다.

## Scheduled Refresh

Spring `@Scheduled`를 사용해 refresh를 실행한다. Refresh 주기는 `@ConfigurationProperties`로 외부 설정에서 관리한다.

```java
@Scheduled(fixedDelayString = "${policy.snapshot-refresh-interval}")
public void scheduledRefresh() {
    refresh();
}
```

1차 구현은 `fixedRate` 대신 `fixedDelay`를 사용한다.

- 이전 refresh가 끝난 후 다음 실행 간격을 계산한다.
- DB 조회 지연으로 동일 instance의 refresh가 겹치는 것을 피한다.
- Policy 변경 반영에 고정된 초 단위 정밀도가 요구되지 않는다.

Scheduled 메서드는 scheduling 진입점만 담당한다. 조회, 검증과 snapshot 교체 로직은 scheduler 시간을 기다리지 않고 직접 테스트할 수 있도록 별도 메서드 또는 협력 객체에 둔다.

각 AI Orchestrator instance는 독립적으로 refresh한다. Instance 간 distributed lock과 memory 동기화는 사용하지 않으며, refresh interval 범위의 eventual consistency를 허용한다.

## Initial State

애플리케이션 시작 시 snapshot은 `initialized=false`인 명시적인 초기 객체다. `null`을 초기 상태 표현으로 사용하지 않는다.

최초 정상 refresh 이전에는 Global Policy를 확인할 수 없는 것으로 판단하고 AI 실행을 fail-closed 처리한다. 이 상태는 특정 tenant row가 없거나 OFF인 정상 상태와 Domain 결과에서 구분할 수 있어야 한다.

DB 초기 조회 성공을 애플리케이션 시작 조건으로 만들지 않는다. 최초 refresh가 실패하더라도 process는 실행 상태를 유지하고 다음 scheduled refresh에서 다시 시도한다.

## Refresh Success and Failure

| Current state | Refresh result | Behavior |
| --- | --- | --- |
| 초기화 전 | 성공 | 새 snapshot 게시, initialized 전환 |
| 초기화 전 | 실패 | 초기 snapshot 유지, fail-closed |
| 정상 snapshot 보유 | 성공 | 새 snapshot으로 원자적 교체 |
| 정상 snapshot 보유 | 실패 | 마지막 정상 snapshot 유지 |

Refresh 성공 시 전체 조회 결과로 만든 snapshot을 한 번에 게시하고 정상 로딩 시각을 갱신한다.

Repository 예외 또는 validation 실패 시 현재 reference를 변경하지 않는다. 특히 refresh 실패를 초기 OFF snapshot으로 되돌리지 않는다.

## Validation Before Publication

새 snapshot을 게시하기 전에 최소한 다음 조건을 검증한다.

- Tenant 식별자가 중복되지 않는다.
- Tenant 식별자가 비어 있지 않다.
- Policy 상태가 지원하는 값이다.
- 필수 값에 `null`이 없다.

Validation은 snapshot 게시 이전에 완료한다. Validation 실패는 refresh 실패이며 부분 결과를 게시하지 않는다.

구체적인 tenant 식별자와 상태 규칙은 Domain 규칙으로 정의하고 Spring validation annotation에 의존하지 않는다.

## Configuration

Snapshot refresh 주기는 Policy 전용 `@ConfigurationProperties` record에 `Duration`으로 바인딩한다.

```properties
policy.snapshot-refresh-interval=5s
```

단위 없는 숫자를 사용하지 않는다. 값은 양수여야 하며 잘못된 설정은 애플리케이션 시작 시 configuration validation으로 거부한다.

이 문서는 refresh interval만 다룬다. User Policy cache TTL과 저장소 timeout은 [002. User Policy Cache-Aside and Fallback Isolation](../decisions/002-user-policy-cache-aside-and-fallback-isolation.md)의 범위다.

## Testing

다음 동작을 단위 테스트한다.

- 초기 snapshot에서는 AI 실행이 fail-closed 처리된다.
- 최초 refresh 성공 후 새 snapshot이 사용된다.
- 최초 refresh 실패 시 초기 snapshot을 유지한다.
- 정상 로딩 이후 repository 실패 시 기존 snapshot을 유지한다.
- Validation 실패 결과가 게시되지 않는다.
- 전체 tenant 상태가 하나의 snapshot으로 교체된다.
- Snapshot의 tenant policy collection을 외부에서 수정할 수 없다.
- 하나의 정책 평가가 동일한 snapshot instance를 사용한다.

Scheduler 주기만큼 실제로 기다리는 테스트는 작성하지 않는다. `PolicySnapshotRefresher`의 핵심 refresh 동작을 직접 호출하고, `@Scheduled` 진입점은 얇게 유지한다.

시간 기반 상태를 검증해야 한다면 시스템 시간을 직접 호출하는 대신 `Clock`을 주입한다.

## Related

- [001. Policy Persistence Access](../decisions/001-policy-persistence-access.md)
- [002. User Policy Cache-Aside and Fallback Isolation](../decisions/002-user-policy-cache-aside-and-fallback-isolation.md)
