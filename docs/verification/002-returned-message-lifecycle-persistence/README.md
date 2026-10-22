# Returned Message Lifecycle Persistence Verification

| 항목 | 값 |
|---|---|
| Status | In Progress |
| Last Verified At | - |
| Verified Commit | - |
| Stale Since | - |
| Stale Reason | - |

이 문서는 해당 Verification의 현재 상태와 TC별 Required 여부, 시나리오 리뷰, 자동화 상태, Assertion 리뷰와 실행 상태의 단일 원천이다. 최신 리뷰의 상세 증적은 각 TC의 `Scenario Review`와 `Assertion Review`에, 최신 실행의 환경, 명령과 상세 집계는 `Last Verification Execution`에 연결된 evidence에 기록한다. 상태 정의와 전환 규칙은 [Verification 상태 관리](../README.md#상태-관리)를 따른다.

현재 production code와 일부 자동화 테스트는 구현되어 있다. 다만 모든 Required TC가 신규 시나리오 리뷰 대기 상태이고, 자동화된 TC의 Assertion 리뷰와 Required TC 전체의 테스트 매핑 및 동일 조건 실행 evidence도 아직 완료되지 않았으므로 구현 존재와 Verification 완료를 구분해 상태를 `In Progress`로 유지한다.

## Objective

Returned Message Topics 후보만 durable lifecycle로 저장되고, REST retry와 여러 AI Orchestrator instance의 경쟁에서도 동일 Trigger와 복귀 구간이 하나의 lifecycle로 수렴하는지 검증한다. 사용자 확인, 만료와 후속 상태 변경은 DB 제약과 조건부 갱신으로 한 번만 성공해야 하며, 경쟁에서 권한을 얻지 못한 처리자는 외부 작업을 시작하지 않아야 한다. 저장된 확인 대기 상태와 만료 기준은 process 재시작 후에도 유지되어야 하고, 외부 I/O 중에는 DB transaction이나 row lock을 유지하지 않아야 한다.

## Verification Basis

| 구분 | 참조 | 이 검증과의 관계 |
|---|---|---|
| Use Case | [Returned Message Topics](../../../../omni-ai/omni-ai-architecture/docs/use-cases/02-returned-message-topics.md) | 복귀 Trigger, 메시지 선정, 사용자 확인, 상태와 멱등성의 사용자 관찰 가능 결과를 정의한다. |
| Upstream Verification | [WebSocket Returned User Presence Trigger](../../../../git/ucware_server6.0_maven/docs/verification/001-returned-user-presence-trigger/README.md) | Desktop 적용 대상, 상태 전이, SID, disconnect/reconnect과 Trigger 생성을 검증한다. |
| Decision | [Returned Message Lifecycle Persistence and Concurrency Control](../../decisions/003-returned-message-lifecycle-persistence.md) | 저장 임계점, 두 unique constraint, conditional update, DB 시각 만료와 transaction 경계를 정의한다. |
| Engineering | [Returned Message Lifecycle Processing](../../engineering/003-returned-message-lifecycle-processing.md) | eligibility 순서, lifecycle 상태, confirmation 경쟁, sweeper와 non-terminal 복구 경계를 구체화한다. |
| Implementation | [ReturnedMessageController](../../../src/main/java/com/ucware/ai_orchestrator/returnedmessage/controller/ReturnedMessageController.java), [ReturnedMessageTriggerService](../../../src/main/java/com/ucware/ai_orchestrator/returnedmessage/application/ReturnedMessageTriggerService.java), [JdbcReturnedMessageRepository](../../../src/main/java/com/ucware/ai_orchestrator/returnedmessage/infrastructure/persistence/JdbcReturnedMessageRepository.java), [ReturnedMessageMaintenanceService](../../../src/main/java/com/ucware/ai_orchestrator/returnedmessage/application/ReturnedMessageMaintenanceService.java) | REST ingress, lifecycle 처리, JDBC 저장과 expiration/recovery 진입점이 구현되어 있다. TC별 보장 범위는 Test Summary와 개별 TC의 자동화 매핑을 기준으로 판단한다. |

## Scope

- 잘못된 계약, 비대상 connection, 유효하지 않은 부재 구간, 만료된 Trigger와 정책·권한 거절 요청이 lifecycle을 생성하지 않는 동작
- 정상 후보가 Message Query 전에 `DETECTED` lifecycle을 생성하는 동작
- `(tenant_id, user_id, absence_period_id)`와 `(tenant_id, trigger_id)` 제약에 의한 생성 멱등성 및 tenant 격리
- unread 결과에 따른 `DETECTED → SKIPPED_NO_MESSAGES` 또는 `AWAITING_CONFIRMATION` 전이
- YES, NO와 expiration 경쟁에서 하나의 조건부 전이만 성공하는 동작
- 확인 만료를 DB 현재 시각으로 판정하고 여러 sweeper가 같은 row를 중복 전이하지 않는 동작
- process 재시작 또는 instance 교체 후 non-terminal lifecycle을 상태별로 복구하는 동작
- 상태와 `executionId` correlation이 일치하는 경우에만 후속 결과를 반영하는 동작
- conditional update 승자만 다음 외부 작업을 수행하고 외부 I/O 중 DB transaction을 유지하지 않는 경계
- Application과 Domain이 JDBC, SQL과 DB schema에 직접 의존하지 않는 계층 경계

## Out of Scope

- Omni AI Server workflow, LLM 품질, Topic grouping과 priority 생성 규칙
- Decision 004가 소유하는 AI start/cancel의 멱등성, 전송 결과 불확실성 및 전체 reconciliation 보장
- Realtime confirmation과 Topic Digest의 exactly-once delivery 및 Outbox 선택
- Message owner 내부의 unread sentinel, 조회 Queue retry와 DB 구현
- Client 로컬 삭제 상태에 따른 Topic과 `messageCount` 보정
- NATS JetStream Trigger Consumer
- 운영 retention, 부하 한계, index 성능과 장시간 soak test
- 운영 DB 제품이 확정되기 전의 driver별 호환성 판정

## Test Strategy

정책과 상태 전이 규칙은 Domain/Application 단위 및 workflow 테스트로 빠르고 결정적으로 검증한다. 외부 Message Query, confirmation sender, AI execution gateway와 result sender는 Test Double로 두어 호출 여부와 횟수를 관찰한다. DB가 보장해야 하는 unique constraint, tenant-scoped lookup, conditional update row count와 `CURRENT_TIMESTAMP` 비교는 운영과 같은 계열의 관계형 DB를 사용하는 persistence integration test로 검증한다.

동시성 테스트는 독립 application instance를 나타내는 두 개 이상의 실행 주체가 같은 DB를 사용하도록 구성하고 barrier 또는 latch로 insert/update 시작점을 맞춘다. 실제 시간이 흐르기를 기다리지 않고 과거·미래 만료 시각과 DB 시각 경계 fixture를 사용한다. 외부 port를 호출할 때 Spring transaction 활성 여부를 관찰하여 외부 I/O와 local DB transaction이 분리되었는지 확인한다.

구현된 범위는 각 TC의 `Automated Test Mapping`에 실제 production class와 테스트 메서드를 연결한다. 아직 매핑되지 않았거나 assertion이 요구사항 일부만 확인하는 항목은 `Planned` 또는 `Partial`로 유지한다. 자동화 상태가 `Automated`가 된 TC는 실제 assertion의 충분성을 `Assertion Review`에서 별도로 검토하고, 설계와 구현의 차이는 `Known Risks and Gaps`에 기록한다.

## Completion Criteria

이 검증은 다음 조건을 만족해야 한다.

1. 저장 전 eligibility의 허용, 거절과 경계값이 자동화되어 있고 거절 경로에서 lifecycle write 및 외부 호출 부재가 검증된다.
2. 두 unique constraint가 순차 및 실제 concurrent insert에서 lifecycle 하나만 허용하며 정상 중복과 계약 충돌을 구분한다.
3. 모든 lifecycle 조회와 갱신이 tenant 범위를 포함하고 다른 tenant/user의 정보를 노출하지 않는다.
4. unread 없음, 확인 대기, 메시지 선정 실패의 상태와 부수 효과가 자동화되어 있다.
5. YES, NO와 expiration 경쟁, 만료 경계 및 중복 응답에서 하나의 상태 전이와 최대 하나의 후속 외부 작업만 관찰된다.
6. sweeper와 재시작 복구가 process-local timer나 lock에 의존하지 않고 저장된 상태와 DB 조건으로 동작한다.
7. 상태와 execution correlation이 일치하지 않는 결과 및 terminal 상태 이후 결과가 lifecycle을 변경하지 않는다.
8. 외부 I/O 호출 시 local DB transaction이 활성 상태가 아니며 Application/Domain에 JDBC 또는 SQL 의존성이 없다.
9. 모든 Required TC가 동일한 clean commit에서 실패와 skip 없이 통과하고 실행 evidence가 연결된다.
10. 모든 Required TC의 시나리오가 현재 Verification Basis와 범위에 대해 리뷰되고 `Approved` 상태다.
11. 모든 Required TC의 실제 테스트 assertion이 Expected Results를 충분히 검증한다고 리뷰되고 `Approved` 상태다.

공통 완료 기준은 [Verification 공통 완료 기준](../README.md#공통-완료-기준)을 따른다.

## Coverage Overview

저장 임계점, lifecycle 생성, 상태 경쟁, 만료, 복구와 transaction 경계가 TC와 어떻게 연결되는지는 [Returned Message Lifecycle Coverage Matrix](lifecycle-coverage-matrix.md)에서 확인한다. Matrix는 탐색용 인덱스이며 Verification 상태와 실행 결과는 이 README에서만 관리한다.

## Test Summary

| TC ID | 검증 항목 | 테스트 수준 | Required | 시나리오 리뷰 | 자동화 상태 | Assertion 리뷰 | 실행 결과 |
|---|---|---|---|---|---|---|---|
| TC-001 | [Lifecycle 저장 임계점](test-cases/TC-001-persistence-threshold.md) | Unit / Workflow | Yes | Ready for Review | Partial | Draft | Not Run |
| TC-002 | [Lifecycle 생성 멱등성과 격리](test-cases/TC-002-lifecycle-creation-idempotency.md) | Persistence Integration / Concurrency | Yes | Ready for Review | Planned | Draft | Not Run |
| TC-003 | [메시지 선정과 확인 대기 전이](test-cases/TC-003-message-selection-transition.md) | Workflow / Persistence Integration | Yes | Ready for Review | Planned | Draft | Not Run |
| TC-004 | [사용자 확인과 만료 경쟁](test-cases/TC-004-confirmation-expiration-race.md) | Workflow / Persistence Integration / Concurrency | Yes | Ready for Review | Planned | Draft | Not Run |
| TC-005 | [Sweeper와 non-terminal 복구](test-cases/TC-005-sweeper-and-recovery.md) | Integration / Concurrency | Yes | Ready for Review | Planned | Draft | Not Run |
| TC-006 | [상태 및 execution correlation 보호](test-cases/TC-006-state-correlation-guard.md) | Domain / Persistence Integration | Yes | Ready for Review | Planned | Draft | Not Run |
| TC-007 | [Transaction과 계층 경계](test-cases/TC-007-transaction-and-layer-boundary.md) | Architecture / Integration | Yes | Ready for Review | Planned | Draft | Not Run |
| TC-008 | [USER_RETURNED REST ingress 계약](test-cases/TC-008-user-returned-rest-contract.md) | Controller / Contract + Application Unit | Yes | Ready for Review | Automated | Ready for Review | Not Run |

## Execution Guide

### Prerequisites

- JDK 17
- 프로젝트 Gradle Wrapper
- 운영 DB 제품 또는 검증용 DB 이미지 확정
- 동시 실행 주체가 공유할 격리된 test database
- 저장소 루트에서 실행한다.

### Verification Tests

아래 명령은 현재 Returned Message package의 자동화 테스트를 실행한다. 아직 모든 Required TC가 실제 테스트에 매핑된 것은 아니므로 이 명령의 성공만으로 Verification 완료를 판정하지 않는다.

```sh
./gradlew test --tests '*returnedmessage*' --rerun-tasks
```

### Full Regression

```sh
./gradlew test --rerun-tasks
```

### Result Locations

- HTML: `build/reports/tests/test/index.html`
- JUnit XML: `build/test-results/test/TEST-*.xml`
- CI: -

## Last Verification Execution

- Execution Summary: -

Required TC 전체를 동일한 clean commit과 환경에서 실행한 evidence가 아직 없다. 개별 TC 실행은 해당 TC의 `Last Execution`에만 기록하며 상위 Verification 실행 결과로 합산하지 않는다.

## Evidence

- Local Report: -
- CI Run: -

`Last Verification Execution`에 연결된 execution summary를 검증 상태와 Test Summary 실행 결과의 기준으로 사용한다.

## Known Risks and Gaps

| 항목 | 영향 | 현재 판단 | 대응 또는 추적 위치 |
|---|---|---|---|
| Decision은 `Proposed`, Engineering 문서는 `Draft` 상태 | 구현 중 상태나 처리 경계가 변경될 수 있다. | 검증 설계도 Draft로 유지 | Basis 변경 시 TC와 Matrix 재검토 |
| 운영 DB 제품 미확정 | unique violation 분류, timestamp와 isolation 동작이 테스트 DB와 다를 수 있다. | DB 확정 전 Verified 판정 불가 | TC-002, TC-004, TC-005 |
| 외부 서비스의 idempotency 지원 미확정 | crash window에서 confirmation 또는 result가 중복 전달될 수 있다. | 이 Verification에서 exactly-once를 주장하지 않음 | Decision 004 및 후속 contract test |
| 운영 timeout, batch, claim lease 값 미확정 | 경계값과 처리량 검증을 확정할 수 없다. | 의미 기반 테스트를 먼저 설계 | 구현 설정 확정 후 TC fixture 갱신 |
| Required TC의 구현·매핑 불완전 | production code와 일부 테스트는 존재하지만 전체 lifecycle, 동시성, transaction 경계의 보장 범위를 판정할 수 없다. | TC-001은 일부, TC-008은 전체 자동화됐고 나머지는 검증 설계 상태 유지 | TC별 Automated Test Mapping과 동일 조건 실행 evidence 추가 |

## Unverified Boundaries and Follow-up Tests

| 경계 | 검증할 내용 | 현재 테스트로 부족한 이유 | Required | 우선순위 |
|---|---|---|---|---|
| REST endpoint | validation, duplicate snapshot 응답과 RFC 9457 Problem Details 실제 응답 | 기본 mapping, 필수 필드, source time 위반과 Problem Details는 자동화되었지만 persistence duplicate snapshot 응답 경계가 미완료다. | Yes | P1 |
| Message owner contract | unread 선정과 `messageSelectionRef` 생성·만료 | 외부 Service가 소유하는 계약이다. | No | P1 |
| Omni AI Server contract | stable execution ID를 사용한 start/status/cancel과 reconciliation | Decision 004 및 외부 서버 계약이 필요하다. | No | P1 |
| Realtime delivery | confirmation/result correlation과 실제 중복 억제 | Realtime Service의 idempotency 지원이 미확정이다. | No | P1 |
| 운영 DB | driver, isolation, lock wait와 index query plan | DB 제품 및 운영 schema가 미확정이다. | No | P1 |
| 부하와 장기 실행 | 대량 동시 Trigger, sweeper 처리량과 connection pool 영향 | 기능 정확성 이후 운영 목표가 필요하다. | No | P2 |
| Client 노출 | 삭제된 원본 제외, Topic 제거와 `messageCount` 보정 | Client 책임이며 상세 protocol이 미확정이다. | No | P2 |

## Overall Assessment

- Assessment Scope: Test Design and Targeted Automation
- Basis: Decision 003과 Engineering 003의 요구사항을 Required TC로 추적했고 REST ingress 계약을 실제 코드와 테스트에 매핑했지만, 모든 Required TC의 신규 시나리오·Assertion 리뷰와 Required TC 전체 실행 evidence는 아직 완료되지 않았다.

### Verified Behaviors

- None

### Remaining Limitations

- TC-001은 일부만 자동화되었고 TC-002~TC-007의 자동화 테스트 매핑은 완료되지 않았다. TC-008은 자동화되고 단독 실행됐지만 상위 Verification 전체 실행 evidence에는 포함되지 않았다.
- 모든 Required TC의 시나리오 리뷰 상태가 `Ready for Review`이며 아직 `Approved`된 TC가 없다.
- TC-008의 Assertion 리뷰는 `Ready for Review`이고, 자동화가 불완전한 TC-001~TC-007의 Assertion 리뷰는 `Draft`다.
- migration과 JDBC 구현은 존재하지만 운영 계열 DB에서의 동시성·transaction 보장은 아직 검증되지 않았다.
- Decision 004 범위의 외부 실행 멱등성과 전체 복구는 이 Verification만으로 보장하지 않는다.

### Conclusion

현재 production 구현과 일부 자동화 테스트는 존재하지만 Required TC의 시나리오·Assertion 리뷰, 전체 테스트 매핑과 실행 evidence가 완료되지 않았으므로 Returned Message lifecycle 동작을 아직 검증한 것으로 판정하지 않는다. 모든 Required TC의 시나리오와 assertion을 `Approved`로 전환하고 실제 테스트 매핑을 완성한 뒤, 같은 clean commit과 환경에서 통과한 evidence를 연결한 경우에만 상태를 `Verified`로 변경한다.
