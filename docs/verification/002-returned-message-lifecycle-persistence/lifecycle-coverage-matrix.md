# Returned Message Lifecycle Coverage Matrix

## Purpose

이 문서는 [Returned Message Lifecycle Persistence Verification](README.md)의 요구사항, 기대 상태, 금지할 부수 효과, Test Case와 향후 자동화 테스트 사이의 연결을 빠르게 탐색하기 위한 요약 인덱스다.

Verification 상태와 TC별 실행 결과의 단일 원천은 [상위 Verification 문서](README.md)다. 상세한 Given/When/Then, 테스트 경계와 실행 기록은 연결된 TC 문서에서 관리한다. 이 Matrix는 그 내용을 대체하거나 독립적인 실행 결과를 갖지 않는다.

## Lifecycle Behavior Coverage

| 처리 단계 | 입력/상태 조건 | 기대 결과 | 금지할 부수 효과 | TC | 자동화 테스트 |
|---|---|---|---|---|---|
| 저장 전 평가 | 지원하는 Desktop connection, 유효한 부재와 Trigger, 정책·권한 허용 | lifecycle insert 시도 | insert 전 Message Query 또는 delivery | [TC-001](test-cases/TC-001-persistence-threshold.md) | Partial |
| 저장 전 평가 | 잘못된 계약, 비대상 connection, 짧거나 잘못된 부재, 만료 Trigger | 요청 거절 또는 비대상 결과 | lifecycle write와 모든 외부 작업 | [TC-001](test-cases/TC-001-persistence-threshold.md) | Partial |
| 저장 전 평가 | Policy/Permission 거절 또는 unavailable | fail-closed, lifecycle 미생성 | Message Query와 AI 실행 | [TC-001](test-cases/TC-001-persistence-threshold.md) | Partial |
| REST ingress | 필수 source field와 source-qualified routingRef가 유효함 | owner field와 routing constraint를 routingRef에서 읽고 application request로 mapping | body 외 owner 추론 | [TC-008](test-cases/TC-008-user-returned-rest-contract.md) | Automated |
| REST ingress | connectionType 누락·비양수 또는 잘못된 body | 4xx Problem Details, application 미호출 | lifecycle write와 외부 작업 | [TC-008](test-cases/TC-008-user-returned-rest-contract.md) | Automated |
| lifecycle 생성 | 새로운 period와 Trigger | `DETECTED` row 하나 생성, 최초 처리 권한 획득 | 중복 row | [TC-002](test-cases/TC-002-lifecycle-creation-idempotency.md) | Planned |
| lifecycle 생성 | 동일 absence period 또는 동일 correlation Trigger retry | 기존 snapshot 반환 | 새 작업과 새 side effect | [TC-002](test-cases/TC-002-lifecycle-creation-idempotency.md) | Planned |
| lifecycle 생성 | 같은 tenant의 Trigger가 다른 user/period를 참조 | 계약 충돌 | 기존 lifecycle 정보 노출 | [TC-002](test-cases/TC-002-lifecycle-creation-idempotency.md) | Planned |
| 메시지 선정 | unread 없음 | `SKIPPED_NO_MESSAGES` | confirmation과 AI 호출 | [TC-003](test-cases/TC-003-message-selection-transition.md) | Planned |
| 메시지 선정 | unread와 유효한 selection 존재 | selection/correlation/만료 저장 후 `AWAITING_CONFIRMATION` | 원문 payload 저장, AI 호출 | [TC-003](test-cases/TC-003-message-selection-transition.md) | Planned |
| 메시지 선정 | 복구 불가능한 Query 실패 | `FAILED`, `failureStage=DETECTION` | confirmation과 AI 호출 | [TC-003](test-cases/TC-003-message-selection-transition.md) | Planned |
| confirmation | 유효한 NO | `DECLINED` | AI execution 생성 | [TC-004](test-cases/TC-004-confirmation-expiration-race.md) | Planned |
| confirmation | 유효하고 재평가가 허용된 YES | execution correlation 저장 후 `PROCESSING`, 승자만 AI start | 중복 execution과 중복 start | [TC-004](test-cases/TC-004-confirmation-expiration-race.md) | Planned |
| confirmation | YES 직전 Policy/Permission 거절 | `BLOCKED_BY_POLICY` | AI start | [TC-004](test-cases/TC-004-confirmation-expiration-race.md) | Planned |
| confirmation | 만료됐거나 다른 전이가 먼저 성공 | 현재 상태의 멱등 결과 | AI start와 terminal 상태 덮어쓰기 | [TC-004](test-cases/TC-004-confirmation-expiration-race.md) | Planned |
| expiration | `AWAITING_CONFIRMATION`이고 DB 시각상 만료 | `EXPIRED` | 다른 상태 변경 | [TC-004](test-cases/TC-004-confirmation-expiration-race.md), [TC-005](test-cases/TC-005-sweeper-and-recovery.md) | Planned |
| recovery | non-terminal 상태가 process 재시작 후 남아 있음 | 상태별 conditional claim 후 재개 | 새 lifecycle 또는 새 correlation 생성 | [TC-005](test-cases/TC-005-sweeper-and-recovery.md) | Planned |
| execution result | `PROCESSING`과 저장된 `executionId` 일치 | 허용된 다음 상태로 한 번 전이 | 다른 execution 결과 반영 | [TC-006](test-cases/TC-006-state-correlation-guard.md) | Planned |
| terminal 상태 | 중복 또는 늦은 결과 수신 | 상태 유지 | terminal 상태 덮어쓰기 | [TC-006](test-cases/TC-006-state-correlation-guard.md) | Planned |
| 외부 작업 | conditional update 또는 claim 성공 | transaction 종료 후 해당 외부 port 호출 | 외부 호출 동안 row lock 유지 | [TC-007](test-cases/TC-007-transaction-and-layer-boundary.md) | Planned |

## Resilience and Consistency Coverage

| 장애 또는 경계 | 기대 동작 | 보호 대상 | TC | 자동화 테스트 |
|---|---|---|---|---|
| 동일 lifecycle concurrent insert | DB constraint로 하나만 생성 | lifecycle 유일성과 최초 작업 단일 수행 | [TC-002](test-cases/TC-002-lifecycle-creation-idempotency.md) | Planned |
| tenant/user가 다른 Trigger 충돌 | 계약 충돌 또는 접근 거절, 정보 비노출 | tenant isolation | [TC-002](test-cases/TC-002-lifecycle-creation-idempotency.md) | Planned |
| YES/NO concurrent update | 하나만 성공 | 사용자 결정 단일성 | [TC-004](test-cases/TC-004-confirmation-expiration-race.md) | Planned |
| YES/expiration concurrent update | `PROCESSING`, `BLOCKED_BY_POLICY` 또는 `EXPIRED` 중 조건을 먼저 만족한 하나로 수렴 | 만료 후 실행 방지 | [TC-004](test-cases/TC-004-confirmation-expiration-race.md) | Planned |
| application instance clock 차이 | DB 시각 기준 동일 판정 | 확인 유효성 일관성 | [TC-004](test-cases/TC-004-confirmation-expiration-race.md) | Planned |
| 다중 sweeper | 한 row의 만료 전이 한 번 성공 | 상태 일관성과 중복 작업 방지 | [TC-005](test-cases/TC-005-sweeper-and-recovery.md) | Planned |
| process 종료 후 재시작 | 저장 상태와 stable correlation으로 재개 | lifecycle 내구성 | [TC-005](test-cases/TC-005-sweeper-and-recovery.md) | Planned |
| stale execution result | conditional update 0건, 상태 유지 | 다른 실행 결과 혼입 방지 | [TC-006](test-cases/TC-006-state-correlation-guard.md) | Planned |
| external I/O 지연 | DB transaction 비활성, 다른 lifecycle DB 작업 가능 | DB connection과 lock | [TC-007](test-cases/TC-007-transaction-and-layer-boundary.md) | Planned |

## Verification Boundary Overview

| 검증 대상 | 계획한 테스트 수준 | 실제 사용할 컴포넌트 | 검증하지 않는 경계 |
|---|---|---|---|
| Eligibility와 persistence threshold | Unit / Workflow | Domain policy, application use case | 실제 upstream 인증과 HTTP mapping |
| USER_RETURNED REST ingress | Controller / Contract + Application Unit | request validation, controller mapping, application source contract, Test Double | upstream Presence 상태 판정 |
| Lifecycle unique constraint | Persistence Integration / Concurrency | 운영 계열 RDB, schema, JDBC adapter | 운영 트래픽 규모와 모든 isolation 조합 |
| Message selection 상태 반영 | Workflow / Persistence Integration | application flow, repository, Query Port Stub | Message owner 내부 DB와 Queue retry |
| Confirmation/expiration 경쟁 | Workflow / Persistence Integration / Concurrency | use case, repository, DB current time | 실제 Client와 network delivery |
| Sweeper와 recovery | Integration / Concurrency | repository, scheduler entry point, recovery claim | 장시간 장애와 운영 backoff |
| Execution correlation | Domain / Persistence Integration | state model, conditional update | Omni AI Server 내부 실행과 Decision 004 전체 계약 |
| Transaction 및 계층 경계 | Architecture / Integration | Spring transaction 경계, external Port Probe | 분산 tracing과 운영 connection pool 수치 |

## Maintenance Rules

- Verification 상태와 실행 결과는 이 문서에 기록하지 않고 [상위 Verification 문서](README.md)에서만 관리한다.
- 상세 Given/When/Then과 테스트 실행 기록은 각 TC 문서에서만 관리한다.
- Decision 003, Engineering 003, schema, 상태 모델, correlation 또는 transaction 경계가 변경되면 이 Matrix와 관련 TC를 함께 갱신한다.
- 구현 후 각 `Planned` 항목을 실제 자동화 테스트 링크로 교체한다.
- Decision 004에 속하는 외부 AI 실행 exactly-once와 reconciliation 요구사항은 이 Matrix에 확장하지 않고 별도 Verification에서 관리한다.
