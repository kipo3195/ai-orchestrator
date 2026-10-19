# 003. Returned Message Lifecycle Persistence and Concurrency Control

Status: Proposed

## Context

Returned Message Topics는 하나의 HTTP 요청 안에서 완료되지 않는다.

```text
USER_RETURNED
→ unread 메시지 선정
→ 사용자 확인 대기
→ YES / NO / timeout
→ AI 실행 요청
→ 결과 전달
```

사용자 확인을 기다리는 동안 AI Orchestrator가 재시작되거나 요청을 처리하는 instance가 변경될 수 있다. 같은 `USER_RETURNED` 요청이 REST timeout으로 재시도될 수 있고, 동일한 자리비움 구간에 서로 다른 Trigger가 중복 전달될 수도 있다. 사용자 응답과 확인 만료 처리도 동시에 실행될 수 있다.

현재 Conversation Start에서 사용하는 in-memory repository, process-local `synchronized`와 `ScheduledExecutorService`는 단일 process 안에서만 상태와 실행 순서를 보호한다. 이 방식은 여러 instance가 동일 요청을 처리하거나 process가 재시작되는 경우 Returned Message lifecycle과 확인 대기 상태를 복원하지 못한다.

반면 Desktop Presence의 모든 상태 변경을 AI Orchestrator의 lifecycle로 저장할 필요는 없다. 지원하지 않는 connection, 짧은 부재와 이미 만료된 Trigger처럼 AI 실행 후보가 되지 않는 요청까지 저장하면 상태 이력과 write 부하만 증가한다.

이 결정은 Returned Message lifecycle을 어디에 저장하고, 여러 instance 사이에서 lifecycle 생성과 상태 전이 경쟁을 어떻게 제어할지를 다룬다. Omni AI Server에 요청을 전달한 이후의 실행 멱등성, 취소와 복구는 [004. AI Execution Dispatch, Idempotency, Cancellation and Recovery](004-ai-execution-dispatch-idempotency-and-recovery.md)에서 별도로 결정한다.

## Decision Drivers

- 명백한 비대상 Presence 요청은 lifecycle로 저장하지 않아야 한다.
- 동일한 `tenantId + userId + absencePeriodId`에는 하나의 lifecycle만 존재해야 한다.
- REST retry와 여러 AI Orchestrator instance의 동시 처리를 허용해야 한다.
- 사용자 응답, 확인 만료와 lifecycle 상태 변경은 한 번만 성공해야 한다.
- 사용자 확인 대기 상태는 process 재시작 후에도 복원할 수 있어야 한다.
- 외부 Message owner, Omni AI Server와 Realtime Service를 호출하는 동안 DB transaction이나 row lock을 유지하면 안 된다.
- Application과 Domain은 JDBC, SQL과 DB schema에 직접 의존하지 않아야 한다.
- 현재 프로젝트의 Spring JDBC 기반과 명시적인 SQL 방식을 재사용해야 한다.

## Considered Options

### Process-local lock and scheduler

Application Service의 `synchronized`, 사용자별 lock 또는 process-local scheduler로 중복 실행과 만료를 처리한다.

단일 process에서는 구현이 단순하지만 instance 간 lock과 timer를 공유하지 못한다. Process가 종료되면 확인 대기 상태와 예약된 만료 작업도 유실된다. 따라서 lifecycle 정확성이나 복구를 보장하는 수단으로 사용할 수 없다.

### Redis lock and TTL

자리비움 구간별 Redis lock으로 중복 처리를 막고 TTL로 확인 만료를 표현한다.

짧은 수명의 중복 억제에는 적합하지만 여러 단계의 lifecycle, 상태 전이 이력과 처리 실패를 별도로 저장해야 한다. Lock 소유권과 만료가 실제 작업 시간보다 먼저 끝나는 경우도 처리해야 하므로 durable lifecycle 저장소를 대체하지 못한다.

### Relational lifecycle with database constraints

관계형 DB에 lifecycle을 저장하고 unique constraint와 현재 상태를 조건으로 한 update로 동시성을 제어한다.

상태와 만료 시각을 재시작 후에도 조회할 수 있고, 여러 instance의 경쟁을 DB가 조정한다. 명시적인 SQL이 필요하지만 현재 프로젝트의 Spring `JdbcClient` 접근 방식과 잘 맞으며 lifecycle 정확성을 위해 별도의 distributed lock을 도입하지 않아도 된다.

## Decision

Returned Message Topics의 durable lifecycle은 AI Orchestrator가 논리적으로 소유하는 관계형 persistence 영역에 저장한다. Infrastructure repository는 Spring `JdbcClient`를 사용한다.

이 lifecycle 저장소는 [001. Policy Persistence Access](001-policy-persistence-access.md)의 읽기 전용 Policy 저장소와 소유권이 다르다. 같은 데이터베이스 제품이나 물리적 cluster를 사용할 수는 있지만 AI Orchestrator가 lifecycle table과 migration을 소유하며 Policy table에 쓰지 않는다. 실제 datasource 분리와 데이터베이스 제품은 배포 설계에서 결정한다.

Application 또는 Domain에는 lifecycle 생성, 조회와 허용된 상태 전이를 표현하는 기술 독립적인 repository interface를 둔다. JDBC 구현, persistence row mapping, unique constraint 해석과 SQL은 Infrastructure에 둔다.

결정의 처리 경계는 다음과 같다.

```text
DB 이전
→ 저장할 가치가 없는 요청 필터링

Lifecycle INSERT
→ 동일 Trigger와 absence period의 생성 경쟁 제어

Conditional UPDATE
→ 사용자 응답, 만료와 후속 단계의 상태 전이 경쟁 제어

External AI execution
→ Decision 004의 공통 실행 계약 적용
```

### Persistence threshold

모든 Presence 변경을 저장하지 않는다. 요청 계약, trusted connection, 부재 구간, Trigger 유효기간과 실행 Policy를 확인해 AI 실행 후보가 된 요청만 lifecycle 생성을 시도한다.

Lifecycle은 Message Query나 Realtime delivery처럼 외부 I/O를 시작하기 전에 생성한다. 동일 lifecycle을 새로 생성할 권한을 얻은 처리자만 최초 작업을 진행한다. 정확한 eligibility 검사 순서, 재평가 시점과 중복 요청 응답은 [003. Returned Message Lifecycle Processing](../engineering/003-returned-message-lifecycle-processing.md)에서 정의한다.

### Lifecycle creation idempotency

동일 자리비움 구간과 동일 Trigger의 중복 lifecycle은 database unique constraint로 차단한다.

```text
UNIQUE (tenant_id, user_id, absence_period_id)
UNIQUE (tenant_id, trigger_id)
```

`triggerId`가 전체 시스템에서 유일하다는 전제에 의존하지 않고 tenant 범위에서 고유하게 관리한다. `absencePeriodId`는 동일 tenant와 user 안에서 하나의 자리비움 구간을 식별한다.

Insert가 unique constraint와 충돌하면 Infrastructure adapter는 tenant 범위를 포함한 해당 key로 기존 lifecycle을 조회한다.

- Absence period constraint 충돌: tenant, user와 absence period가 모두 일치하면 다른 `triggerId`로 전달됐더라도 동일 복귀 구간의 중복으로 처리한다.
- Trigger constraint 충돌: tenant, user와 absence period가 모두 일치할 때만 동일 Trigger retry로 처리한다.
- 같은 `triggerId`가 다른 user 또는 absence period를 가리키면 계약 위반으로 처리한다.

어떤 충돌에서도 기존 lifecycle 정보를 다른 tenant나 user에게 반환하지 않는다.

Process-local lock, JVM `synchronized`와 Redis distributed lock은 이 멱등성의 정확성 보장 수단으로 사용하지 않는다. Trigger source의 debounce 또는 중복 제거는 부하를 줄이는 최적화로 사용할 수 있지만 DB constraint를 대체하지 않는다.

### Atomic state transition

Lifecycle 생성 이후 경쟁 가능한 상태 변경은 현재 상태와 필요한 correlation 값을 `WHERE` 조건에 포함한 conditional update로 수행한다.

```sql
UPDATE returned_message_request
SET status = :nextStatus,
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = :tenantId
  AND request_id = :requestId
  AND status = :expectedStatus;
```

만료가 적용되는 사용자 응답은 `confirmation_expires_at > CURRENT_TIMESTAMP` 조건을 추가하고, AI 실행 결과를 반영할 때는 저장된 `execution_id`를 함께 비교한다.

영향받은 row가 `1`이면 현재 처리자가 상태 전이에 성공한 것이다. `0`이면 다른 처리자가 이미 전이했거나, 현재 상태·만료 시각·correlation 값이 요청과 일치하지 않는 것이다. 이 경우 외부 작업을 새로 시작하지 않고 현재 lifecycle을 조회해 멱등 결과를 반환한다.

별도의 version column은 상태와 correlation 조건만으로 보호할 수 없는 동일 상태 내 갱신 경쟁이 실제로 필요한 경우 추가한다. 초기 구현에서는 business state와 correlation 값을 조건으로 한 update를 우선한다.

### Confirmation expiration

사용자 확인의 유효성은 DB에 저장한 절대 시각으로 판단한다. `confirmation_expires_at` 생성과 비교에는 DB 시각을 기준으로 사용하여 application instance 간 clock 차이가 결과를 바꾸지 않게 한다.

YES와 NO를 처리하는 conditional update는 현재 상태와 만료 시각을 함께 검증한다. 만료 시각이 지났지만 row가 아직 대기 상태로 남아 있어도 늦은 응답은 처리되지 않는다.

Spring `@Scheduled` sweeper는 논리적으로 만료된 대기 row를 실제 만료 상태로 materialize한다. 여러 instance에서 동시에 실행되어도 현재 상태와 만료 시각을 조건으로 한 update 하나만 성공하므로 단순 상태 만료를 위해 distributed scheduler lock을 추가하지 않는다.

Sweeper 실행 시점은 사용자 응답의 유효성을 결정하지 않는다. Sweeper의 batch, index와 실행 주기는 Engineering 문서에서 정의한다.

### Transaction boundary

Lifecycle insert와 각 conditional update는 짧은 DB transaction으로 실행한다. Message Query, AI Context 조회, Omni AI Server 호출과 Realtime delivery 같은 외부 I/O 중에는 DB transaction이나 row lock을 유지하지 않는다.

외부 작업 전에는 conditional update로 해당 단계를 수행할 권한을 획득하고, 외부 작업 후에는 correlation을 확인하는 별도의 conditional update로 결과를 반영한다. DB transaction과 외부 서비스 transaction을 하나의 원자적 transaction으로 묶지 않는다.

### Guarantee boundary

이 결정이 보장하는 범위는 다음과 같다.

- 동일 absence period와 Trigger의 lifecycle 중복 생성 방지
- DB 안에서 경쟁하는 상태 전이의 단일 성공
- 사용자 응답과 만료의 일관된 판정
- lifecycle 상태의 process 재시작 후 복원

이 결정만으로 Omni AI Server의 실행이나 Realtime delivery가 정확히 한 번 수행되는 것은 보장하지 않는다. 외부 호출의 불확실한 결과, 실행 취소, 재시도와 복구는 Decision 004의 stable execution ID와 reconciliation 계약을 적용한다.

## Consequences

### Positive

- 명백한 비대상 Presence 요청은 lifecycle write를 발생시키지 않는다.
- 동일 자리비움 구간과 동일 Trigger의 중복 생성을 DB가 최종적으로 차단한다.
- 여러 AI Orchestrator instance가 동일 요청을 받아도 하나의 상태 전이만 성공한다.
- 사용자 확인 대기와 만료 상태를 process 재시작 후에도 복원할 수 있다.
- 사용자 응답과 timeout의 경쟁을 별도의 distributed lock 없이 처리할 수 있다.
- 외부 서비스 지연이 DB transaction과 connection을 장시간 점유하지 않는다.

### Negative

- Lifecycle table, index, migration과 retention 관리가 필요하다.
- Unique constraint 충돌과 conditional update `0`건을 정상적인 멱등 결과 또는 계약 위반으로 구분해야 한다.
- DB 시각을 사용하는 SQL과 만료 처리에 대한 integration test가 필요하다.
- 외부 호출의 멱등성과 복구를 위해 Decision 004의 Omni AI Server 계약이 추가로 필요하다.
- Sweeper 주기만큼 논리적 만료 상태의 materialization이 지연될 수 있다.

## Non-goals

- 운영 관계형 데이터베이스 제품과 물리적 datasource 구성 선택
- 전체 DDL과 migration script 정의
- Returned Message의 전체 상태와 처리 순서 정의
- Message Query와 Realtime API의 구체적인 HTTP 또는 메시지 계약
- Omni AI Server 내부 workflow와 agent 실행 방식
- 외부 AI 실행과 delivery의 exactly-once 보장
- NATS JetStream Trigger Consumer 구현

## Related

- [001. Policy Persistence Access](001-policy-persistence-access.md)
- [004. AI Execution Dispatch, Idempotency, Cancellation and Recovery](004-ai-execution-dispatch-idempotency-and-recovery.md)
- [003. Returned Message Lifecycle Processing](../engineering/003-returned-message-lifecycle-processing.md)
- [Package Structure](../architecture/package-structure.md)
