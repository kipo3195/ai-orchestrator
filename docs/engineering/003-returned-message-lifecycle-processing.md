# 003. Returned Message Lifecycle Processing

Status: Draft

## Purpose

Returned Message Topics의 실행 후보 선정, durable lifecycle 생성, 사용자 확인, AI 실행 correlation과 결과 전달 순서를 정의한다.

이 문서는 다음 결정을 구현하는 구체적인 상태와 처리 경계를 설명한다.

- [003. Returned Message Lifecycle Persistence and Concurrency Control](../decisions/003-returned-message-lifecycle-persistence.md)
- [004. AI Execution Dispatch, Idempotency, Cancellation and Recovery](../decisions/004-ai-execution-dispatch-idempotency-and-recovery.md)

## Scope

- `USER_RETURNED` Trigger의 입력 검증과 eligibility 평가 순서
- Returned Message lifecycle 생성과 중복 요청 처리
- 메시지 선정과 사용자 확인 요청
- YES, NO와 timeout의 경쟁 처리
- 기능 Policy와 Permission 재평가
- 공통 AI execution lifecycle과의 correlation
- AI 결과와 Realtime delivery 상태 분리
- 만료 sweeper와 non-terminal lifecycle 복구 경계
- 설정과 테스트 기준

## Non-scope

- Presence 생성과 `absencePeriodId` 발급 주체
- Message owner의 unread 선정 내부 구현
- Omni AI Server의 workflow, agent와 LLM 처리
- Prompt와 AI 응답 생성 규칙
- 기능별 Policy 저장과 변경 API
- Realtime transport의 exactly-once delivery와 Outbox 선택
- 운영 데이터베이스 제품과 전체 migration script

## Component Boundary

Returned Message는 feature-first package 안에서 Application, Domain과 Infrastructure를 분리한다. 아래 이름은 역할을 나타내며 실제 구현 시 프로젝트 naming convention에 맞출 수 있다.

```text
returnedmessage/
├── application
│   ├── HandleUserReturned
│   ├── HandleReturnedMessageConfirmation
│   ├── HandleReturnedMessageExecutionResult
│   ├── ExpireReturnedMessageConfirmations
│   └── port
│       ├── ReturnedMessageRepository
│       ├── ReturnedMessageSelectionRepository
│       ├── ReturnedMessageQuery
│       ├── ReturnedMessageConfirmationSender
│       ├── AiExecutionGateway
│       └── ReturnedMessageResultSender
├── domain
│   ├── ReturnedMessageRequest
│   ├── ReturnedMessageSelection
│   ├── ReturnedMessageStatus
│   ├── ReturnedMessageDecision
│   └── ReturnedMessageFailureStage
└── infrastructure
    ├── persistence
    ├── client
    ├── messaging
    └── scheduling
```

- Controller 또는 Trigger consumer는 입력 검증과 Application use case 호출만 담당한다.
- Application은 전체 처리 순서, port 호출과 transaction 경계를 조정한다.
- Domain은 lifecycle 상태와 허용된 전이를 표현한다.
- JDBC row, SQL, external request/response DTO와 Redis/NATS/HTTP 규격은 Infrastructure에 둔다.
- Policy 평가는 공통 `AiExecutionPolicyService`를 사용하며 Returned Message package가 Policy 저장소를 직접 조회하지 않는다.

## Trigger Contract

`USER_RETURNED` Trigger에는 최소한 다음 correlation 정보가 필요하다.

```text
triggerId
tenantId
userId
absencePeriodId
offlineSince
returnedAt
triggerExpiresAt
connection context 또는 이를 검증할 수 있는 식별자
```

- `triggerId`는 tenant 안에서 Trigger delivery retry를 식별한다.
- `absencePeriodId`는 tenant와 user 안에서 하나의 부재 구간을 식별한다.
- 현재 단계에서는 Messenger Client Ingress가 확정한 source-qualified request body의
  `routingRef.tenantId`, `routingRef.userId`를 owner의 단일 입력 출처로 사용한다.
- AI Orchestrator는 `routingRef`에서 owner를 추출해 policy, permission, persistence와
  message query에 사용하며 최상위 `tenantId`, `userId`를 중복으로 받지 않는다.
- 별도 인증 context와 request body를 비교하는 방식은 서비스 간 인증 계약을 정의하는 후속 ADR에서 다룬다.
- `offlineSince < returnedAt <= triggerExpiresAt`처럼 시간 관계를 먼저 검증한다.

잘못된 body 계약과 누락되거나 양수가 아닌 `connectionType`은 lifecycle을 생성하지 않고 거절한다. AI Orchestrator는 legacy `connectionType`의 값별 의미나 Returned Message 적용 대상 여부를 재판정하지 않고, 양수 값을 opaque routing constraint로 보관한다. `connectType 1, 2` 적용 대상 판정은 Messenger Client Ingress가 담당한다. 오류를 REST로 반환하는 경우 프로젝트의 RFC 9457 Problem Details 규칙을 따른다. 현재 body 기반 처리는 Client 원시 입력을 신뢰한다는 의미가 아니며, Messenger Client Ingress가 Client 입력을 검증하고 source-qualified Event를 구성한다는 호출 경계를 전제로 한다.

## Eligibility and Persistence Threshold

Lifecycle 생성 전 다음 순서로 실행 후보 여부를 평가한다.

1. 필수 식별자와 시간 형식을 검증한다.
2. Request body의 `connectionType`이 존재하고 양수인지 확인한다. 값별 의미와 기능 적용 대상은 판정하지 않는다.
3. `offlineSince`와 `returnedAt`이 유효한 부재 구간인지 확인한다.
4. 현재 DB 시각 기준으로 Trigger가 만료되지 않았는지 확인한다.
5. 부재 시간이 `minAwayDuration` 이상이고 장기 부재 제외 기준을 위반하지 않는지 확인한다.
6. Global → Feature → User Policy를 순서대로 평가한다.
7. 메시지 조회와 AI 실행에 필요한 현재 Permission을 확인한다.
8. 모든 기본 조건이 허용된 경우 lifecycle insert를 시도한다.

Policy 또는 Permission을 확인할 수 없으면 lifecycle을 생성하지 않고 재시도 가능한 unavailable 오류를 반환한다. Policy가 명시적으로 비활성화된 요청과 명백한 비대상 요청은 정상적인 거절 결과로 종료한다.

초기 평가 결과는 오래 유지되는 실행 권한이 아니다. 사용자 확인을 기다리는 동안 Policy와 Permission이 바뀔 수 있으므로 YES 처리 후 AI 실행을 선점하기 직전에 다시 평가한다.

## Lifecycle Creation

Lifecycle은 Message Query를 수행하기 전에 `DETECTED` 상태로 생성한다. Repository insert는 다음 unique key의 보호를 받는다.

```text
(tenant_id, user_id, absence_period_id)
(tenant_id, trigger_id)
```

처리 결과는 다음과 같이 구분한다.

| Insert result | Application behavior |
| --- | --- |
| 생성 성공 | 현재 처리자가 최초 메시지 선정 단계 진행 |
| 동일 absence period의 기존 lifecycle | 기존 lifecycle 상태 반환, 새 작업 시작하지 않음 |
| 동일 Trigger와 동일 correlation | 기존 lifecycle 상태 반환 |
| 동일 Trigger와 다른 user/absence period | 계약 충돌로 거절 |
| DB timeout 또는 실패 | lifecycle 생성 실패, 외부 작업 시작하지 않음 |

Unique constraint 이름과 database exception은 Infrastructure에서 해석한다. Application에는 생성됨, 이미 존재함, correlation 충돌과 저장소 실패처럼 기술 독립적인 결과를 반환한다.

## Lifecycle State Model

초기 상태 모델은 다음과 같다.

| Status | 의미 | Terminal |
| --- | --- | --- |
| `DETECTED` | lifecycle 생성 완료, 메시지 선정 전 또는 복구 대기 | 아니오 |
| `AWAITING_CONFIRMATION` | 메시지 선정 완료, 사용자 확인 대기 | 아니오 |
| `PROCESSING` | `executionId`가 할당되고 공통 AI 실행 처리 중 | 아니오 |
| `DELIVERY_PENDING` | AI 실행 성공, 결과 전달 전 또는 재시도 대기 | 아니오 |
| `SKIPPED_NO_MESSAGES` | 조건에 맞는 unread 메시지가 없음 | 예 |
| `BLOCKED_BY_POLICY` | 재평가한 Policy 또는 Permission이 실행을 거절 | 예 |
| `DECLINED` | 사용자가 NO 선택 | 예 |
| `EXPIRED` | 확인 유효기간 만료 | 예 |
| `COMPLETED` | AI 결과 전달 완료 | 예 |
| `FAILED` | 메시지 선정 또는 AI 실행이 복구 불가능하게 실패 | 예 |
| `CANCELLED` | 시작된 AI 실행이 취소로 확정 | 예 |
| `DELIVERY_FAILED` | AI 실행은 성공했지만 결과 전달이 최종 실패 | 예 |

`FAILED`에는 `failureStage`와 외부에 노출해도 안전한 failure reason을 함께 저장해 메시지 선정 실패와 AI 실행 실패를 구분한다. 내부 예외, credential과 외부 응답 원문은 저장하거나 API로 노출하지 않는다.

허용된 주요 전이는 다음과 같다.

```text
DETECTED
├── AWAITING_CONFIRMATION
├── SKIPPED_NO_MESSAGES
└── FAILED

AWAITING_CONFIRMATION
├── PROCESSING
├── BLOCKED_BY_POLICY
├── DECLINED
└── EXPIRED

PROCESSING
├── DELIVERY_PENDING
├── FAILED
└── CANCELLED

DELIVERY_PENDING
├── COMPLETED
└── DELIVERY_FAILED
```

Terminal 상태에서는 다른 상태로 전이하지 않는다. Delivery를 운영에서 다시 시도하는 경우 `DELIVERY_FAILED`를 되돌리는 대신 별도 delivery attempt를 생성하거나 명시적인 재개 command를 사용한다.

## Message Selection and Confirmation

Lifecycle 생성에 성공한 처리자는 tenant, user와 absence period 범위로 Message owner에 unread 메시지를 조회한다. Message Query는 읽기 전용이며 AI Orchestrator는 Messenger 데이터의 소유권을 가져오지 않는다.

현재 WebSocket Service adapter는 `MSG_UNREAD`의 첫 페이지를 `reqcount=30`, `reqpage=1`로 조회하고 결과의 `data[].msg_key`를 선택 항목으로 사용한다. 응답에 전체 건수 필드가 없으므로 `unreadCount`는 이 실행에서 선택된 항목 수이며 최대 30이다. AI Orchestrator는 UUID `messageSelectionRef`를 생성하고, 선택 메타데이터와 항목을 각각 `returned_message_selection`, `returned_message_selection_item`에 저장한다. 선택 항목은 source system, item type, source item key와 원래 조회 순서를 보관하며 메시지 본문은 저장하지 않는다.

```text
messageSelectionRef 1 ── N source item key
```

동일 lifecycle request에 이미 유효한 선택이 저장되어 있으면 복구 시 새 UUID를 만들지 않고 기존 선택을 재사용한다. `selection-ttl`이 지난 선택은 새 confirmation에 사용할 수 없다.

- 대상 메시지가 없으면 `DETECTED → SKIPPED_NO_MESSAGES`로 전이한다.
- 대상 메시지가 있으면 confirmation correlation, confirmation delivery 상태 `PENDING`과 DB 시각 기준 `confirmationExpiresAt`을 저장하면서 `DETECTED → AWAITING_CONFIRMATION`으로 전이한다.
- 조회가 복구 불가능하게 실패하면 `DETECTED → FAILED`로 전이하고 `failureStage=DETECTION`을 기록한다.

Confirmation 전송에는 lifecycle request ID와 confirmation correlation을 포함한다. 성공하면 confirmation delivery 상태를 `SENT`로 변경한다. `AWAITING_CONFIRMATION`이지만 delivery 상태가 `PENDING`인 row는 만료 전까지 전송 복구 대상이다.

Realtime Service가 idempotency key를 지원하면 confirmation correlation을 사용한다. 지원하지 않는 경우 전송 성공 직후 process가 종료되면 복구 과정에서 사용자에게 중복 prompt가 전달될 수 있으며, 이는 알려진 delivery 한계로 기록한다. 중복 prompt가 전달되어도 응답 상태 전이는 DB에서 한 번만 성공해야 한다.

외부 Message Query와 confirmation 전송 중에는 DB transaction을 유지하지 않는다.

## Confirmation Handling

Confirmation 요청은 인증된 `tenantId`, `userId`, lifecycle request ID와 confirmation correlation으로 조회한다. URL이나 payload의 request ID만으로 다른 사용자의 lifecycle을 반환하지 않는다.

### NO

NO는 다음 조건을 모두 포함한 conditional update로 처리한다.

```text
tenant와 user 일치
request와 confirmation correlation 일치
status = AWAITING_CONFIRMATION
confirmation_expires_at > DB current time
```

성공하면 `DECLINED`로 전이한다. `0`건이면 현재 lifecycle을 다시 조회해 이미 거절됨, 만료됨, 처리 중 또는 terminal 상태를 멱등 응답으로 반환한다. NO 처리에서는 AI 실행을 생성하지 않는다.

### YES

YES는 다음 순서로 처리한다.

```text
현재 lifecycle과 confirmation 유효성 조회
→ Global / Feature / User Policy 재평가
→ Permission 재평가
→ messageSelectionRef와 lifecycle owner로 유효한 선택 및 source item key 조회
→ stable executionId 생성
→ AWAITING_CONFIRMATION에서 PROCESSING으로 conditional update
→ 전이 성공 시에만 선택 항목을 포함한 Omni AI start command 수행
```

YES 시점에는 `MSG_UNREAD`를 다시 실행하지 않는다. 최초 복귀 처리에서 확정한 source item key 집합을 사용하여 사용자가 확인한 대상과 AI 실행 대상이 달라지지 않게 한다. Client가 보낸 `messageSelectionRef`는 lifecycle에 저장된 값과 일치하는지 먼저 검증하고, selection 조회도 request, tenant, user와 absence period를 모두 조건으로 제한한다.

Policy 또는 Permission이 거절되면 아직 만료되지 않은 `AWAITING_CONFIRMATION` row를 `BLOCKED_BY_POLICY`로 전이하고 AI를 호출하지 않는다. 재평가 중 NO 또는 expiration이 먼저 상태를 변경하면 YES의 conditional update는 `0`건이 되며 AI 실행을 시작하지 않는다.

`PROCESSING` 전이에는 `executionId`를 함께 저장한다. 이후 dispatch timeout이나 process 종료가 발생해도 새로운 ID를 만들지 않고 Decision 004에 따라 같은 ID로 reconciliation한다.

## Confirmation Expiration

응답 유효성은 `confirmation_expires_at`과 DB 현재 시각의 비교로 결정한다. Sweeper 실행 여부는 유효성 판단에 영향을 주지 않는다.

Sweeper는 다음 조건에 해당하는 row를 batch 단위로 `EXPIRED`로 변경한다.

```text
status = AWAITING_CONFIRMATION
confirmation_expires_at <= DB current time
```

- `(status, confirmation_expires_at)` 조회를 지원하는 index를 둔다.
- 한 transaction에서 무제한 row를 갱신하지 않고 설정된 batch 크기로 처리한다.
- 여러 instance가 실행해도 conditional update 결과가 `0`인 row는 이미 처리된 것으로 간주한다.
- 단순 만료 materialization에는 distributed scheduler lock을 사용하지 않는다.
- 사용자별 timer나 process-local `ScheduledFuture`를 생성하지 않는다.

## AI Execution Correlation

`PROCESSING` 이후에는 [004. AI Execution Dispatch, Idempotency, Cancellation and Recovery](../decisions/004-ai-execution-dispatch-idempotency-and-recovery.md)를 따른다.

- Start, retry, cancel, status query와 result는 저장된 동일 `executionId`를 사용한다.
- HTTP timeout이나 connection failure를 `FAILED` 또는 `CANCELLED`로 즉시 변환하지 않는다.
- Dispatch 결과가 불확실하면 `PROCESSING`을 유지하고 공통 reconciliation 대상으로 등록한다.
- Callback 또는 조회 결과는 `status = PROCESSING AND execution_id = :executionId` 조건으로 반영한다.
- 다른 ID의 결과와 terminal 상태 이후 도착한 stale 결과는 상태를 변경하지 않는다.

공통 실행 결과를 Returned Message lifecycle에 다음과 같이 반영한다.

| Common execution result | Returned Message transition |
| --- | --- |
| accepted / running / unknown | `PROCESSING` 유지 |
| succeeded | 결과 correlation 저장 후 `DELIVERY_PENDING` |
| failed | `FAILED`, `failureStage=AI_EXECUTION` |
| cancelled | `CANCELLED` |

Omni AI Server의 세부 workflow state를 Returned Message lifecycle에 복제하지 않는다.

## Result Delivery

`DELIVERY_PENDING`은 AI 실행이 이미 성공했다는 뜻이다. Realtime delivery에는 lifecycle request ID, `executionId`와 안정적인 delivery correlation을 사용한다.

- Delivery 성공: `DELIVERY_PENDING → COMPLETED`
- 복구 불가능하거나 허용된 retry를 모두 소진한 실패: `DELIVERY_PENDING → DELIVERY_FAILED`
- Timeout처럼 결과가 불확실하면 같은 delivery correlation으로 상태를 확인하거나 delivery만 재시도한다.

Delivery retry는 Omni AI start command를 다시 호출하지 않는다. AI 응답 원문을 Orchestrator DB에 저장할지, Omni AI Server의 result reference만 저장할지는 실제 결과 조회 계약과 데이터 보존 정책에서 결정한다.

## Recovery

Non-terminal lifecycle은 상태별로 복구한다.

| Status | Recovery responsibility |
| --- | --- |
| `DETECTED` | 메시지 선정 작업을 conditional claim 후 재개 |
| `AWAITING_CONFIRMATION` | confirmation delivery가 `PENDING`이면 동일 correlation으로 전송 재개, 이후 응답 대기와 만료 처리 |
| `PROCESSING` | 공통 AI execution reconciliation 수행 |
| `DELIVERY_PENDING` | 저장된 result correlation으로 delivery만 재개 |

복구 worker의 claim에는 owner token과 유한한 lease를 사용하고 DB conditional update로 획득한다. Lease는 외부 작업의 완료를 의미하지 않으며, 만료된 claim을 다른 instance가 인계할 수 있게 한다. 외부 호출 중 DB transaction이나 row lock을 유지하지 않는다.

Message Query처럼 읽기 전용 작업은 재실행될 수 있다. Confirmation과 result delivery처럼 side effect가 있는 작업은 stable correlation을 사용한다. 외부 서비스가 idempotency 또는 상태 조회를 제공하지 않는 경우 정확히 한 번 전달을 주장하지 않는다.

## Duplicate Request Response

중복 Trigger나 confirmation에 대해서는 새 작업을 시작하지 않고 현재 lifecycle snapshot을 반환한다. REST 응답에는 호출자가 다음 동작을 판단하는 데 필요한 최소 상태만 포함하고 내부 claim, retry 횟수, SQL 오류와 외부 서비스 응답 원문은 노출하지 않는다.

다음 경우는 정상 멱등 요청이 아니다.

- 같은 tenant의 `triggerId`가 다른 user 또는 absence period를 가리킴
- confirmation correlation이 lifecycle과 일치하지 않음
- 인증된 tenant/user와 lifecycle 소유자가 다름

이 경우 RFC 9457 Problem Details로 계약 충돌 또는 접근 거절을 반환하며 기존 lifecycle 정보를 노출하지 않는다.

## Transaction Boundary

하나의 DB transaction에는 lifecycle insert 또는 하나의 상태 전이와 그에 필요한 local row 기록만 포함한다.

다음 작업 중에는 DB transaction을 유지하지 않는다.

- Message Query
- Policy 또는 Permission 외부 조회
- Confirmation 전송
- Omni AI start, cancel과 status query
- Result delivery

External I/O 전후에는 상태와 correlation을 조건으로 한 별도 update를 사용한다. External I/O와 DB write 사이의 crash window는 state별 recovery와 idempotency key로 복구한다.

## Configuration

구체적인 운영 값은 배포 환경에서 재정의할 수 있도록 `@ConfigurationProperties`로 관리한다.

```properties
returned-message.min-away-duration=5m
returned-message.confirmation-timeout=2m
returned-message.expiration-sweep-interval=30s
returned-message.expiration-batch-size=100
returned-message.recovery-claim-duration=30s
```

- Duration에는 단위를 명시한다.
- Duration과 batch size는 양수여야 한다.
- Confirmation timeout은 client UX와 전체 요청 lifecycle 요구를 기준으로 정한다.
- Claim duration은 외부 client timeout보다 길게 잡는 값이 아니라 작업 인계 주기와 heartbeat 여부를 함께 고려한다.
- AI execution reconciliation backoff와 retry 설정은 공통 execution configuration에서 관리한다.

## Testing

다음 동작을 자동화 테스트한다.

### Domain and application

- 지원하지 않는 connection, 잘못된 시간 구간과 짧은 부재는 저장하지 않는다.
- Policy 또는 Permission 거절과 unavailable은 fail-closed 처리한다.
- 동일 absence period와 Trigger의 동시 insert에서 하나만 생성된다.
- 같은 Trigger의 다른 correlation은 계약 충돌로 처리된다.
- 대상 메시지가 없으면 `SKIPPED_NO_MESSAGES`로 전이한다.
- YES, NO와 expiration 경쟁에서 하나의 전이만 성공한다.
- 만료 시각 이후 YES와 NO는 AI 실행을 시작하지 않는다.
- Policy 재평가 중 NO 또는 expiration이 먼저 성공하면 AI를 호출하지 않는다.
- AI result의 `executionId`가 다르면 lifecycle을 변경하지 않는다.
- Delivery 실패가 AI 재실행으로 이어지지 않는다.

### Persistence integration

- 두 unique constraint가 실제 concurrent insert를 차단한다.
- 상태와 expiration 조건이 포함된 update가 예상 row 수를 반환한다.
- DB 현재 시각을 기준으로 expiration 경계가 일관되게 처리된다.
- Sweeper가 batch로 만료시키고 이미 전이된 row를 변경하지 않는다.
- Tenant 조건 없는 lifecycle 조회 또는 update가 존재하지 않는다.

### API and messaging

- 중복 요청은 새 side effect 없이 같은 lifecycle 상태를 반환한다.
- 계약 충돌과 접근 거절은 lifecycle 정보를 노출하지 않는다.
- REST 오류는 status, `application/problem+json`과 required Problem Details field를 검증한다.
- Callback과 result event의 duplicate 및 out-of-order delivery를 검증한다.

실제 시간이 흐르기를 기다리는 테스트 대신 주입 가능한 시간 경계 또는 DB test clock 전략을 사용한다. DB `CURRENT_TIMESTAMP` 동작을 검증해야 하는 경우 JDBC integration test로 확인한다.

## Known Limitations

- Omni AI Server가 Decision 004의 idempotent start/cancel과 status query 계약을 제공해야 end-to-end recovery를 완성할 수 있다.
- Realtime Service가 idempotency key를 지원하지 않으면 confirmation 또는 result가 중복 전달될 수 있다.
- Delivery exactly-once와 Outbox 도입 여부는 아직 결정하지 않았다.
- 운영 retry 횟수, reconciliation backoff와 retention 기간은 배포 요구를 바탕으로 확정해야 한다.
- Policy와 Permission이 확인 후 외부 실행 직전에 다시 변경되는 짧은 race를 완전히 제거하려면 정책 version 또는 authorization token 계약이 추가로 필요하다.

## Related

- [003. Returned Message Lifecycle Persistence and Concurrency Control](../decisions/003-returned-message-lifecycle-persistence.md)
- [004. AI Execution Dispatch, Idempotency, Cancellation and Recovery](../decisions/004-ai-execution-dispatch-idempotency-and-recovery.md)
- [002. User Policy Cache-Aside Implementation](002-user-policy-cache-aside-implementation.md)
- [Package Structure](../architecture/package-structure.md)
