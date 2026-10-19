# 004. AI Execution Dispatch, Idempotency, Cancellation and Recovery

Status: Proposed

## Context

AI Orchestrator는 기능별 Policy, cooldown과 duplicate execution prevention을 평가하고 실행 상태를 관리한다. 실제 AI workflow, agent 실행과 LLM 처리는 Omni AI Server가 소유한다.

기능별 lifecycle에서 실행 가능 여부를 원자적으로 결정하더라도 Omni AI Server에 실행 요청을 전달하는 순간에는 두 서비스 사이에 원자적 transaction이 없다. 다음과 같은 불확실한 구간이 생길 수 있다.

```text
Orchestrator가 실행 상태를 선점
→ Omni AI Server에 요청 전송
→ Omni AI Server가 요청 접수 또는 실행 시작
→ 응답을 저장하기 전에 timeout 또는 process 종료
```

HTTP timeout이나 connection 종료는 Omni AI 실행이 시작되지 않았다는 의미가 아니다. 새로운 식별자로 요청을 재전송하면 동일한 AI 작업이 중복 실행될 수 있고, 재전송하지 않으면 Orchestrator 상태가 영구적으로 처리 중에 남을 수 있다.

취소도 하나의 HTTP 응답만으로 확정할 수 없다. 취소 요청과 정상 완료가 경쟁할 수 있으며, 취소 요청의 timeout은 실제 취소 여부를 알려주지 않는다. AI 실행은 완료됐지만 결과 delivery만 실패한 경우에는 AI를 다시 실행하지 않고 delivery만 재시도해야 한다.

이 문제는 Returned Message에 한정되지 않는다. Omni AI Server에 실행을 위임하는 모든 기능이 공통으로 따라야 할 실행 식별, 멱등성, 취소와 복구 계약이 필요하다.

## Decision Drivers

- 기능별 cooldown과 deduplication 이후에도 동일한 외부 AI 작업이 중복 실행되지 않아야 한다.
- Network timeout과 실제 실행 실패 또는 취소를 구분해야 한다.
- AI Orchestrator가 재시작되거나 처리 instance가 변경되어도 실행 상태를 복구할 수 있어야 한다.
- 실행 요청, 취소와 상태 조회는 반복 호출되어도 동일한 실행을 가리켜야 한다.
- 취소와 정상 완료의 경쟁 결과가 호출 instance나 응답 순서에 따라 임의로 바뀌면 안 된다.
- AI 실행 재시도와 결과 delivery 재시도를 분리해야 한다.
- 두 서비스 사이에 distributed transaction을 도입하지 않아야 한다.
- Omni AI Server의 workflow와 conversation state 소유권을 AI Orchestrator로 이동하지 않아야 한다.

## Considered Options

### Treat the synchronous response as the execution truth

HTTP 요청이 성공하면 실행 성공, timeout이나 connection failure이면 실행 실패로 처리한다.

구현은 단순하지만 timeout 직전에 Omni AI Server가 요청을 접수한 경우를 구분할 수 없다. 실패로 기록하고 새 요청을 보내면 중복 실행될 수 있으며, 취소와 완료의 경쟁도 복구할 수 없다.

### Retry with a new execution identifier

전송 결과를 확인하지 못하면 새로운 실행 식별자를 발급해 다시 요청한다.

각 HTTP 요청은 구분할 수 있지만 동일한 business operation이 여러 AI 실행으로 생성될 수 있다. Network retry를 기능 재실행과 구분하지 못하므로 사용하지 않는다.

### Stable execution identifier with idempotent commands and reconciliation

AI Orchestrator가 외부 호출 전에 stable `executionId`를 생성하고, Omni AI Server가 같은 ID의 시작과 취소 요청을 멱등하게 처리한다. 전송 결과가 불확실하면 같은 ID로 상태를 조회하거나 같은 command를 재전송해 복구한다.

두 서비스 사이의 원자적 transaction 없이도 하나의 논리 실행을 계속 추적할 수 있다. 이를 위해 Omni AI Server가 execution ID 기반 중복 제거와 상태 확인 계약을 제공해야 한다.

## Decision

Omni AI Server에 위임하는 모든 AI 실행은 AI Orchestrator가 외부 호출 전에 생성한 stable `executionId`로 식별한다. 최초 요청과 모든 retry, cancellation, callback, result와 reconciliation은 같은 `tenantId + executionId`를 사용한다.

기능별 lifecycle ID와 `executionId`는 구분한다.

- Feature lifecycle ID: Returned Message 등 기능 자체의 요청과 상태를 식별한다.
- `executionId`: Omni AI Server에서 수행되는 하나의 논리 AI 실행을 식별한다.

하나의 기능 lifecycle이 AI 실행 단계에 진입할 때 `executionId`를 한 번 생성하고 durable state에 저장한다. Transport timeout이나 process 재시작을 이유로 새로운 `executionId`를 생성하지 않는다.

### Start command idempotency

Omni AI Server의 실행 시작 command는 `tenantId + executionId`를 idempotency key로 사용한다.

- 처음 보는 ID이면 실행을 생성하고 접수 상태를 반환한다.
- 이미 존재하는 ID와 동일한 immutable request이면 기존 실행의 접수 또는 현재 상태를 반환한다.
- 같은 ID에 다른 tenant 또는 다른 immutable request가 전달되면 conflict로 거절한다.
- Retry는 같은 `executionId`와 같은 immutable request를 사용한다.

AI Orchestrator는 request fingerprint 또는 동등한 correlation 정보를 저장하여 retry payload가 최초 요청과 같은 논리 작업인지 검증할 수 있어야 한다. Omni AI Server 내부의 semantic cache나 workflow 구현은 이 결정의 범위가 아니다.

### Dispatch result semantics

Transport 결과와 실행 결과를 구분한다.

| Dispatch observation | Orchestrator interpretation | Next action |
| --- | --- | --- |
| 접수 응답 수신 | 실행이 Omni AI Server에 존재함 | correlation 저장, 결과 대기 |
| 명시적 validation 또는 conflict 거절 | 실행을 시작할 수 없음 | 요청 오류 또는 실패로 확정 |
| timeout / connection failure | 시작 여부를 알 수 없음 | 실패·취소로 확정하지 않고 같은 ID로 reconciliation |
| 같은 ID가 이미 존재함 | 중복 HTTP 요청이지만 동일 논리 실행 | 기존 실행 상태 사용 |

Timeout 상태에서 cooldown 또는 duplicate execution guard를 즉시 해제하지 않는다. 실제 실행이 없거나 terminal 상태라는 사실을 확인하기 전에 새로운 기능 실행을 허용하면 중복 AI 실행이 생길 수 있기 때문이다.

### Cancellation semantics

취소는 terminal 상태를 즉시 기록하는 동작이 아니라 하나의 idempotent command다.

```text
active or uncertain
→ CANCEL_REQUESTED
→ CANCELLED 또는 이미 확정된 terminal result
```

- 같은 `executionId`에 대한 취소 command는 반복 호출할 수 있어야 한다.
- 취소 요청의 timeout은 `CANCELLED`를 의미하지 않는다.
- Omni AI Server가 취소를 확정한 경우에만 `CANCELLED`로 전이한다.
- 취소보다 정상 완료가 먼저 terminal 상태로 확정되었다면 완료 결과를 유지한다.
- Terminal 상태에 도달한 뒤 도착한 오래된 callback이나 응답은 현재 상태를 덮어쓰지 않는다.

사용자 요청 취소, feature lifecycle 취소와 client connection 종료 중 어떤 사건이 Omni AI 실행 취소로 이어지는지는 기능별 Engineering 문서에서 정의한다. 단순한 HTTP client disconnect를 자동으로 AI 실행 취소로 해석하지 않는다.

### State observation and reconciliation

Omni AI Server는 `tenantId + executionId`로 실행의 존재 여부와 현재 또는 terminal 상태를 확인할 수 있는 계약을 제공해야 한다. Durable terminal event나 callback은 정상 처리 latency를 줄이는 수단으로 사용할 수 있지만, event 유실과 Orchestrator 재시작 후 복구를 위해 상태 조회가 최종 reconciliation 경로가 된다.

AI Orchestrator는 non-terminal 또는 dispatch 결과가 불확실한 실행을 durable하게 조회할 수 있어야 한다. Recovery worker는 DB conditional claim으로 하나의 instance만 특정 reconciliation 시도를 수행하게 하고, 외부 상태 조회 중에는 DB transaction을 유지하지 않는다.

Reconciliation은 다음 원칙을 따른다.

- 실행이 존재하면 관찰한 상태를 같은 `executionId`에 반영한다.
- 실행이 존재하지 않는다는 authoritative 결과가 확인되면 같은 `executionId`로 start command를 다시 전송할 수 있다.
- 조회 자체가 timeout이면 terminal 상태로 추정하지 않고 다음 reconciliation 대상으로 남긴다.
- Callback과 조회 결과는 현재 상태와 `executionId`를 조건으로 한 update로 반영한다.
- 최대 재시도 횟수 초과만으로 실행이 없다고 간주하거나 deduplication을 해제하지 않는다.

오래 해결되지 않는 실행은 운영상 `UNKNOWN`으로 노출할 수 있지만, 이는 Omni AI 실행이 종료됐다는 의미가 아니다. 자동으로 새로운 실행을 허용하려면 별도의 business risk 결정이 필요하다.

### Ownership boundary

Omni AI Server는 실제 workflow 실행 상태와 terminal execution result의 authoritative owner다. AI Orchestrator는 다음 상태를 소유한다.

- 기능 lifecycle과 실행 허용 여부
- `executionId`와 기능 요청의 correlation
- 마지막으로 관찰한 외부 실행 상태
- dispatch, cancellation과 reconciliation 시도 상태
- cooldown과 duplicate execution guard에 필요한 상태
- 결과 전달 상태

AI Orchestrator는 prompt 처리, agent 단계, LLM 호출과 AI conversation state를 복제하지 않는다.

### Result and delivery separation

AI 실행의 terminal result와 client delivery 상태를 분리한다.

```text
AI execution SUCCEEDED
→ result correlation 저장
→ delivery pending
→ delivered 또는 delivery failed
```

AI 실행이 성공한 뒤 delivery만 실패한 경우 같은 `executionId`의 AI 실행을 다시 시작하지 않는다. 저장된 result 또는 result reference를 사용해 delivery 단계만 재시도한다. Delivery 자체의 exactly-once 보장과 Outbox 도입은 전송 채널별 별도 결정으로 다룬다.

### Transaction boundary

Local execution state의 claim과 결과 반영은 각각 짧은 DB transaction으로 수행한다. Omni AI Server start, cancel, status query와 result delivery 중에는 DB transaction이나 row lock을 유지하지 않는다.

두 서비스 사이에 distributed transaction을 사용하지 않는다. Stable execution ID, idempotent command, conditional update와 reconciliation을 조합해 불확실한 구간을 복구한다.

## Consequences

### Positive

- Transport retry가 새로운 AI 실행으로 변환되지 않는다.
- Timeout, 취소 요청과 terminal 결과를 서로 다른 상태로 유지할 수 있다.
- Orchestrator 재시작 후 Omni AI Server의 실제 실행 상태와 다시 동기화할 수 있다.
- 기능별 cooldown과 deduplication이 외부 실행 상태를 확인하기 전에 풀리는 것을 방지한다.
- AI 실행 성공 후 delivery 실패 시 AI를 다시 실행하지 않을 수 있다.
- Distributed transaction 없이 서비스 간 실행 correlation을 유지한다.

### Negative

- Omni AI Server에 idempotent start/cancel과 status query 계약이 필요하다.
- AI Orchestrator에 durable execution correlation과 reconciliation worker가 필요하다.
- 불확실한 실행은 확인될 때까지 기능별 중복 실행을 차단할 수 있다.
- Callback, polling과 retry가 경쟁하므로 conditional update와 stale response 처리가 필요하다.
- 장기간 `UNKNOWN`인 실행을 운영에서 관찰하고 처리하는 절차가 필요하다.

## Non-goals

- Omni AI Server 내부 workflow, agent와 LLM 실행 방식
- Semantic cache와 vector retrieval
- 기능별 cooldown 시간과 중복 판단 business key
- Returned Message의 사용자 확인과 메시지 선정 규칙
- 전송 채널별 exactly-once delivery 보장과 Outbox 선택
- 구체적인 HTTP, gRPC 또는 NATS schema
- 운영 retry 횟수, backoff와 reconciliation 주기 기본값

## Related

- [003. Returned Message Lifecycle Persistence and Concurrency Control](003-returned-message-lifecycle-persistence.md)
- [003. Returned Message Lifecycle Processing](../engineering/003-returned-message-lifecycle-processing.md)
- [Package Structure](../architecture/package-structure.md)
