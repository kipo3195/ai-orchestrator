# AI Execution Policy Chain Verification

| 항목 | 값 |
|---|---|
| Status | Verified |
| Last Verified At | 2026-10-14 17:45 KST |
| Verified Commit | `ac204af` |
| Stale Since | - |
| Stale Reason | - |

이 문서는 해당 Verification의 현재 상태와 TC별 자동화·실행 상태의 단일 원천이다. 최신 실행의 환경, 명령과 상세 집계는 `Last Verification Execution`에 연결된 evidence에 기록한다. 상태 정의와 전환 규칙은 [Verification 상태 관리](../README.md#상태-관리)를 따른다.

## Objective

사용자 정보가 없는 AI 실행에는 Global → Feature를, 사용자 기반 AI 실행에는 Global → Feature → 전체 User Policy를 순서대로 적용하여 필요한 모든 정책이 허용된 경우에만 AI 실행이 수행되는지 검증한다. 정책 누락, snapshot 미초기화와 저장소 장애에서는 fail-closed로 거절하고, 상위 정책 거절 시 불필요한 하위 저장소 조회와 실제 AI 호출이 발생하지 않아야 한다.

## Verification Basis

| 구분 | 참조 | 이 검증과의 관계 |
|---|---|---|
| Engineering | [Policy Immutable Snapshot Refresh](../../engineering/001-policy-immutable-snapshot-refresh.md) | Global/Feature 불변 snapshot, 원자적 게시, 초기 상태와 refresh 실패 동작을 정의한다. |
| Engineering | [User Policy Cache-Aside Implementation](../../engineering/002-user-policy-cache-aside-implementation.md) | 전체 User Policy의 cache-aside, fail-closed와 기능 적용 경계를 정의한다. |
| Decision | [Policy Persistence Access](../../decisions/001-policy-persistence-access.md) | Global/Feature 전체 조회 및 User 단건 조회의 persistence 방식을 정의한다. |
| Decision | [User Policy Cache-Aside and Fallback Isolation](../../decisions/002-user-policy-cache-aside-and-fallback-isolation.md) | Redis 장애, DB fallback, negative cache와 동시 실행 제한을 정의한다. |
| Implementation | [AiExecutionPolicyService](../../../src/main/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyService.java) | 두 평가 진입점, 평가 순서, short-circuit와 결정 사유를 구현한다. |
| Implementation | [AiPolicySnapshot](../../../src/main/java/com/ucware/ai_orchestrator/policy/domain/AiPolicySnapshot.java) / [InMemoryAiPolicyStore](../../../src/main/java/com/ucware/ai_orchestrator/policy/infrastructure/memory/InMemoryAiPolicyStore.java) | Global/Feature 정책의 불변성과 원자적 교체를 구현한다. |
| Implementation | [CacheAsideUserAiPolicyProvider](../../../src/main/java/com/ucware/ai_orchestrator/policy/application/CacheAsideUserAiPolicyProvider.java) | User Policy cache-aside와 fallback 격리를 구현한다. |
| Implementation | [ConversationStartService](../../../src/main/java/com/ucware/ai_orchestrator/conversationstart/application/ConversationStartService.java) | AI 실행 직전 통합 정책 평가와 실행 차단을 적용한다. |

## Scope

- `evaluateFeature(...)`가 동일한 snapshot에서 Global → Feature를 평가하고 User Provider를 호출하지 않는 동작
- `evaluateFeatureForUser(...)`가 Global → Feature → 전체 User Policy를 평가하고 상위 거절 시 즉시 반환하는 동작
- Global/Feature 정책 누락의 기본 OFF, snapshot 미초기화와 User 저장소 unavailable의 fail-closed 동작
- Global/Feature 데이터의 불변성, 전체 교체, 원자적 게시와 refresh 실패 시 마지막 정상 snapshot 유지
- User Policy의 Redis hit/miss/장애, 제한된 DB fallback, negative cache와 cache write best effort
- JDBC Global/Feature 전체 조회, User 단건 조회와 Redis key/value/TTL adapter 계약
- Conversation Start 실행 직전 정책 재평가, 거절 상태 전이와 AI port 미호출
- 다수 reader와 snapshot writer 사이의 process-local 동시 접근 안전성

정책 결정은 다음 규칙을 따른다.

| Global | Feature | User | 결과 | 사유 |
|---|---|---|---|---|
| 미초기화 | 평가 안 함 | 조회 안 함 | 거절 | `GLOBAL_POLICY_UNAVAILABLE` |
| 누락/OFF | 평가 안 함 | 조회 안 함 | 거절 | `GLOBAL_AI_DISABLED` |
| ON | 누락/OFF | 조회 안 함 | 거절 | `FEATURE_AI_DISABLED` |
| ON | ON | unavailable | 거절 | `USER_POLICY_UNAVAILABLE` |
| ON | ON | 미설정 | 거절 | `USER_POLICY_NOT_CONFIGURED` |
| ON | ON | OFF | 거절 | `USER_AI_DISABLED` |
| ON | ON | ON | 허용 | `ALLOWED` |

사용자 없는 평가는 User 열을 적용하지 않으며 Global과 Feature가 모두 ON이면 허용한다.

## Out of Scope

- 사용자별 기능 정책과 `UserFeaturePolicyProvider`: 현재 User Policy는 사용자의 전체 AI 사용 여부만 표현하며 독립 관리 요구가 생길 때 확장한다.
- `(tenantId, userId, feature)` 저장·캐시 모델과 정책 변경 API
- 이벤트 기반 cache invalidation과 여러 Orchestrator instance 사이의 강한 snapshot 일관성
- 운영 DB 제품 및 driver 호환성, 실제 Redis server 장애와 timeout
- 실제 scheduler 시간 경과, HTTP AI adapter와 result delivery의 end-to-end 검증

## Test Strategy

Domain과 Application 단위 테스트로 snapshot 불변성, 정책 결정 행렬, short-circuit와 User cache-aside 상태 변환을 검증한다. JDBC slice 테스트는 H2 schema와 row mapping을 실제로 사용하고, Redis adapter는 `StringRedisTemplate`을 Mock으로 두어 key/value/TTL 계약에 집중한다. Conversation Start는 scheduler가 받은 Runnable을 직접 실행하고 AI port를 Mock으로 관찰하여 시간 대기 없이 정책 거절과 실제 호출 차단을 검증한다. 동시성 테스트는 `CountDownLatch`로 reader와 writer의 시작을 맞추고 반복 교체 중 관찰되는 snapshot instance를 확인한다. 실제 운영 DB, Redis server, scheduler 시간 경과와 HTTP 통신은 후속 통합 경계로 남긴다.

## Completion Criteria

이 검증은 다음 조건을 만족해야 한다.

1. 두 평가 API의 허용·거절 경로와 결정 사유가 자동화되어 있다.
2. Global/Feature/User의 누락과 unavailable 상태가 fail-closed로 검증된다.
3. 상위 정책 거절과 사용자 없는 실행에서 User Provider 미호출이 검증된다.
4. Global/Feature가 하나의 불변 snapshot으로 생성되고 원자적으로 교체된다.
5. JDBC, Redis와 User DB fallback의 핵심 adapter 계약이 자동화되어 있다.
6. Conversation Start에서 Global, Feature와 User 정책별 AI port 호출 차단 및 전체 허용 경로가 검증된다.
7. 모든 Required TC가 동일한 clean commit에서 실패와 skip 없이 통과하고 실행 evidence가 연결된다.

공통 완료 기준은 [Verification 공통 완료 기준](../README.md#공통-완료-기준)을 따른다.

## Coverage Overview

정책 상태, 기대 결정, short-circuit와 실제 AI 실행 결과가 TC 및 자동화 테스트와 어떻게 연결되는지는 [AI Execution Policy Coverage Matrix](policy-coverage-matrix.md)에서 확인한다. Matrix는 탐색용 인덱스이며 Verification 상태와 실행 결과는 이 README에서만 관리한다.

## Test Summary

| TC ID | 검증 항목 | 테스트 수준 | Required | 자동화 상태 | 실행 결과 |
|---|---|---|---|---|---|
| TC-001 | [Snapshot 초기 상태](test-cases/TC-001-snapshot-initialization.md) | Unit | Yes | Automated | Passed |
| TC-002 | [Snapshot refresh와 게시](test-cases/TC-002-snapshot-refresh-and-publication.md) | Unit / Integration | Yes | Automated | Passed |
| TC-003 | [Global/Feature 평가](test-cases/TC-003-global-feature-evaluation.md) | Unit | Yes | Automated | Passed |
| TC-004 | [User Policy resolution](test-cases/TC-004-user-policy-resolution.md) | Unit / Integration | Yes | Automated | Passed |
| TC-005 | [정책 체인 short-circuit](test-cases/TC-005-policy-chain-short-circuit.md) | Unit | Yes | Automated | Passed |
| TC-006 | [Conversation Start 실행 차단](test-cases/TC-006-conversation-start-enforcement.md) | Workflow | Yes | Automated | Passed |
| TC-007 | [동시 snapshot 게시](test-cases/TC-007-concurrent-snapshot-publication.md) | Unit | Yes | Automated | Passed |

## Execution Guide

### Prerequisites

- JDK 17
- Gradle Wrapper 9.7.1
- 외부 DB와 Redis server는 필요하지 않다.
- 저장소 루트에서 실행한다.

### Verification Tests

이 명령은 해당 Verification의 모든 Required TC 자동화 테스트를 포함한다.

```sh
./gradlew test \
  --tests '*policy.*' \
  --tests '*ConversationStartServiceTests' \
  --rerun-tasks
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

- Execution Summary: [2026-10-14 verification execution](evidence/2026-10-14-ac204af.md)

구현·테스트·Draft 문서가 포함된 clean commit `ac204af`에서 모든 Required TC와 전체 회귀 테스트를 실행했다. 실행 후 작성한 evidence와 상태 변경은 별도 기록 커밋으로 관리한다.

## Evidence

- Local Report: `build/reports/tests/test/index.html`
- CI Run: -
- Historical Global-only Execution: [2026-10-13 verification execution](evidence/2026-10-13-2707097.md)

`Last Verification Execution`에 연결된 execution summary를 검증 상태와 Test Summary 실행 결과의 기준으로 사용한다. Historical evidence는 Global-only 구현 단계의 기록이며 현재 통합 Verification 완료 판정에는 사용하지 않는다.

## Known Risks and Gaps

| 항목 | 영향 | 현재 판단 | 대응 또는 추적 위치 |
|---|---|---|---|
| 운영 DB schema 및 driver 미검증 | Feature key, boolean과 transaction 동작이 H2와 다를 수 있다. | 운영 DB 확정 전 수용 | 운영 DB integration test |
| 실제 Redis 장애/timeout 미검증 | Mock이 실제 client timeout과 connection failure를 재현하지 못한다. | 후속 테스트 필요 | TC-004의 Unverified Boundary |
| 사용자별 기능 정책 미구현 | 사용자 단위로 특정 기능만 제어할 수 없다. | 현재 요구사항 범위 밖 | 관리 요구 발생 시 `UserFeaturePolicyProvider` 도입 |

## Unverified Boundaries and Follow-up Tests

| 경계 | 검증할 내용 | 현재 테스트로 부족한 이유 | Required | 우선순위 |
|---|---|---|---|---|
| 운영 DB → JDBC repository → snapshot | 실제 DB에서 Global/Feature를 일관되게 조회하고 잘못된 row를 거절하는지 검증 | 현재 JDBC slice는 H2를 사용한다. | No | P1 |
| 실제 Redis → cache-aside provider | timeout, connection failure, value serialization과 TTL을 검증 | 현재 Redis adapter는 Mock을 사용한다. | No | P1 |
| Scheduler → policy → HTTP AI adapter | 실제 예약 시간 이후 정책 변경이 HTTP 호출을 차단하는지 검증 | 현재 Runnable 직접 실행과 Mock AI port를 사용한다. | No | P1 |
| 다중 Orchestrator instance | 각 instance의 refresh 간 eventual consistency와 운영 허용 범위 확인 | 현재 단일 JVM AtomicReference만 검증한다. | No | P2 |
| User Feature Policy | 사용자별 기능 설정의 저장, 캐시와 평가 순서 검증 | 해당 기능이 아직 요구되거나 구현되지 않았다. | No | P3 |

## Overall Assessment

- Assessment Scope: Unit, H2 JDBC Integration Slice, Workflow Unit and JVM Concurrency
- Basis: clean commit `ac204af`에서 모든 Required TC를 포함하는 Verification Tests 49개와 전체 회귀 테스트 66개가 실패·skip 없이 통과했다.

### Verified Behaviors

- Global → Feature → 전체 User Policy 순서의 허용·거절과 결정 사유
- 상위 정책 거절 및 사용자 없는 평가에서 하위 User Provider를 호출하지 않는 short-circuit
- 미초기화, 정책 누락과 User 저장소 unavailable 상태의 fail-closed
- Global/Feature 불변 snapshot의 전체 교체, refresh 실패 시 마지막 정상 상태 유지와 JVM 내 원자적 게시
- User Policy의 Redis cache-aside, 제한된 DB fallback, negative cache와 cache write best effort
- Conversation Start 실행 직전 정책 재평가와 정책 거절 시 AI port 호출 차단

### Remaining Limitations

- 운영 DB, 실제 Redis, scheduler 및 HTTP adapter 연결 경계는 검증하지 않았다.
- 사용자별 기능 정책은 현재 범위에 포함되지 않는다.

### Conclusion

clean commit `ac204af`에서 모든 Required TC와 전체 회귀 테스트가 통과했으므로 현재 정의된 자동화 검증 범위에서 `Verified`로 판정한다. 운영 DB, 실제 Redis와 외부 adapter를 포함하는 통합 경계는 후속 검증 대상으로 유지한다.
