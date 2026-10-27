# AI Execution Policy Coverage Matrix

## Purpose

이 문서는 AI 실행 정책의 요구사항, 결정 결과, 부수 효과, Test Case와 자동화 테스트 사이의 연결을 한 화면에서 탐색하기 위한 요약 인덱스다.

Verification 상태와 TC별 실행 결과의 단일 원천은 [AI Execution Policy Chain Verification](README.md)과 연결된 execution evidence다. 상세한 Given/When/Then, 테스트 경계와 자동화 테스트 매핑은 연결된 TC 문서에서 관리한다. 이 Matrix는 그 내용을 대체하거나 독립적인 실행 결과를 갖지 않는다.

## Evaluation Coverage

| 실행 유형 | 정책 조건 | 기대 결정 | 보장할 부수 효과 | TC | 자동화 테스트 |
|---|---|---|---|---|---|
| 사용자 기반 | Snapshot 미초기화 | `GLOBAL_POLICY_UNAVAILABLE` | User Provider 미호출, fail-closed | [TC-001](test-cases/TC-001-snapshot-initialization.md), [TC-005](test-cases/TC-005-policy-chain-short-circuit.md) | [`rejectsWhenSnapshotHasNotBeenInitialized`](../../../src/test/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyServiceTests.java) |
| 사용자 기반 | Global 누락 또는 OFF | `GLOBAL_AI_DISABLED` | User Provider 미호출, AI Port 미호출 | [TC-003](test-cases/TC-003-global-feature-evaluation.md), [TC-005](test-cases/TC-005-policy-chain-short-circuit.md), [TC-006](test-cases/TC-006-conversation-start-enforcement.md) | [`rejectsWhenGlobalPolicyIsDisabled`](../../../src/test/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyServiceTests.java), [`scheduledExecutionIsRejectedWhenTenantGlobalPolicyTurnsOffBeforeExecution`](../../../src/test/java/com/ucware/ai_orchestrator/conversationstart/application/ConversationStartServiceTests.java) |
| 사용자 기반 | Global ON, Feature 누락 또는 OFF | `FEATURE_AI_DISABLED` | User Provider 미호출, AI Port 미호출 | [TC-003](test-cases/TC-003-global-feature-evaluation.md), [TC-005](test-cases/TC-005-policy-chain-short-circuit.md), [TC-006](test-cases/TC-006-conversation-start-enforcement.md) | [`rejectsFeatureBeforeResolvingUserPolicy`](../../../src/test/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyServiceTests.java), [`scheduledExecutionIsRejectedWhenFeaturePolicyTurnsOffBeforeExecution`](../../../src/test/java/com/ucware/ai_orchestrator/conversationstart/application/ConversationStartServiceTests.java) |
| 사용자 기반 | Global/Feature ON, User OFF | `USER_AI_DISABLED` | 실행 `REJECTED`, AI Port 미호출 | [TC-005](test-cases/TC-005-policy-chain-short-circuit.md), [TC-006](test-cases/TC-006-conversation-start-enforcement.md) | [`mapsOverallUserPolicyRejections`](../../../src/test/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyServiceTests.java), [`scheduledExecutionIsRejectedWhenOverallUserPolicyIsDisabled`](../../../src/test/java/com/ucware/ai_orchestrator/conversationstart/application/ConversationStartServiceTests.java) |
| 사용자 기반 | Global/Feature ON, User 미설정 | `USER_POLICY_NOT_CONFIGURED` | Negative cache, fail-closed | [TC-004](test-cases/TC-004-user-policy-resolution.md), [TC-005](test-cases/TC-005-policy-chain-short-circuit.md) | [`negativelyCachesMissingDatabasePolicy`](../../../src/test/java/com/ucware/ai_orchestrator/policy/application/CacheAsideUserAiPolicyProviderTests.java), [`mapsOverallUserPolicyRejections`](../../../src/test/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyServiceTests.java) |
| 사용자 기반 | Global/Feature ON, User 조회 불가 | `USER_POLICY_UNAVAILABLE` | 장애 결과 미캐시, fail-closed | [TC-004](test-cases/TC-004-user-policy-resolution.md), [TC-005](test-cases/TC-005-policy-chain-short-circuit.md) | [`returnsUnavailableWithoutCachingWhenDatabaseFails`](../../../src/test/java/com/ucware/ai_orchestrator/policy/application/CacheAsideUserAiPolicyProviderTests.java), [`mapsOverallUserPolicyRejections`](../../../src/test/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyServiceTests.java) |
| 사용자 기반 | Global/Feature/User 모두 ON | `ALLOWED` | AI Port 호출 및 결과 라우팅 | [TC-005](test-cases/TC-005-policy-chain-short-circuit.md), [TC-006](test-cases/TC-006-conversation-start-enforcement.md) | [`allowsEnabledOverallUserPolicyAfterGlobalAndFeature`](../../../src/test/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyServiceTests.java), [`completedResultIsRoutedUsingExecutionRoutingContext`](../../../src/test/java/com/ucware/ai_orchestrator/conversationstart/application/ConversationStartServiceTests.java) |
| 사용자 없음 | Global/Feature 모두 ON | `ALLOWED` | User Provider 미호출 | [TC-003](test-cases/TC-003-global-feature-evaluation.md) | [`evaluatesGlobalAndFeatureWithoutResolvingUserPolicy`](../../../src/test/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyServiceTests.java) |

## Resilience and Consistency Coverage

| 장애 또는 경계 | 기대 동작 | 보호 대상 | TC | 자동화 테스트 |
|---|---|---|---|---|
| 최초 snapshot refresh 실패 | 미초기화 상태 유지, fail-closed | 정책 미확인 상태의 잘못된 허용 방지 | [TC-001](test-cases/TC-001-snapshot-initialization.md) | [`keepsUninitializedSnapshotWhenFirstScheduledRefreshFails`](../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/scheduling/PolicySnapshotRefresherTests.java) |
| 정상 운영 중 refresh 실패 | 마지막 정상 snapshot 유지 | 일시적 DB 장애의 실행 경로 전파 방지 | [TC-002](test-cases/TC-002-snapshot-refresh-and-publication.md) | [`keepsLastValidSnapshotWhenScheduledRefreshFails`](../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/scheduling/PolicySnapshotRefresherTests.java) |
| 새 snapshot validation 실패 | 잘못된 snapshot 미게시 | 오염된 정책의 부분 게시 방지 | [TC-002](test-cases/TC-002-snapshot-refresh-and-publication.md) | [`keepsLastValidSnapshotWhenNewSnapshotValidationFails`](../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/scheduling/PolicySnapshotRefresherTests.java) |
| Redis hit | DB 미호출 | User Policy DB 부하 감소 | [TC-004](test-cases/TC-004-user-policy-resolution.md) | [`returnsCachedStatusWithoutCallingRepository`](../../../src/test/java/com/ucware/ai_orchestrator/policy/application/CacheAsideUserAiPolicyProviderTests.java) |
| Redis miss 또는 read failure | 제한된 DB fallback | Cache 장애 시 정책 확인 지속 | [TC-004](test-cases/TC-004-user-policy-resolution.md) | [`usesDatabaseFallbackWhenCacheReadFails`](../../../src/test/java/com/ucware/ai_orchestrator/policy/application/CacheAsideUserAiPolicyProviderTests.java) |
| DB fallback 동시 한도 초과 | DB 미호출, `Unavailable` | Redis 장애 시 DB 과부하 방지 | [TC-004](test-cases/TC-004-user-policy-resolution.md) | [`rejectsConcurrentFallbackBeforeCallingRepository`](../../../src/test/java/com/ucware/ai_orchestrator/policy/application/CacheAsideUserAiPolicyProviderTests.java) |
| Redis write 실패 | DB에서 얻은 현재 결과 유지 | Cache 장애가 정책 결과를 오염시키는 문제 방지 | [TC-004](test-cases/TC-004-user-policy-resolution.md) | [`keepsDatabaseResultWhenCacheWriteFails`](../../../src/test/java/com/ucware/ai_orchestrator/policy/application/CacheAsideUserAiPolicyProviderTests.java) |
| 동시 snapshot 조회/교체 | 완전한 이전 또는 새 snapshot만 관찰 | Global/Feature와 `loadedAt` 혼합 방지 | [TC-007](test-cases/TC-007-concurrent-snapshot-publication.md) | [`concurrentReadersObserveOnlyCompleteSnapshotsDuringReplacement`](../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/memory/InMemoryAiPolicyStoreTests.java) |
| 정책 평가 중 execution 취소 | 취소 상태 유지, 완료 결과 미적용 | 취소·재입장 이후 잘못된 결과 반영 방지 | [TC-006](test-cases/TC-006-conversation-start-enforcement.md) | [`resultDoesNotCompleteExecutionAfterRoomSessionIsCancelled`](../../../src/test/java/com/ucware/ai_orchestrator/conversationstart/application/ConversationStartServiceTests.java) |

## Verification Boundary Overview

| 검증 대상 | 현재 테스트 수준 | 실제 사용 컴포넌트 | 검증하지 않은 경계 |
|---|---|---|---|
| 정책 결정과 short-circuit | Application Unit | `AiExecutionPolicyService`, Domain snapshot | Spring 전체 wiring |
| Snapshot refresh | Unit | `PolicySnapshotRefresher`, immutable snapshot | 실제 scheduler 시간 경과 |
| Global/Feature persistence | H2 JDBC Integration Slice | 실제 SQL, `JdbcClient`, H2 schema | 운영 DB driver와 isolation |
| User cache-aside | Application/Adapter Unit | Provider와 semaphore | 실제 Redis timeout과 connection failure |
| User persistence | H2 JDBC Integration Slice | 실제 SQL, `JdbcClient`, H2 schema | 운영 DB driver |
| Conversation Start 적용 | Workflow Unit | 실제 application flow와 in-memory repository | 실제 scheduler와 HTTP AI adapter |
| Snapshot 동시성 | JVM Concurrency Unit | 실제 `AtomicReference` Store | 다중 Orchestrator instance 일관성 |

## Maintenance Rules

- Verification 상태와 실행 결과는 이 문서에 중복 기록하지 않고 [README](README.md)에서만 관리한다.
- 상세 Given/When/Then과 테스트 실행 기록은 각 TC 문서에서만 관리한다.
- 정책 평가 순서, `AiExecutionPolicyReason`, short-circuit, workflow 적용 또는 TC 매핑이 변경되면 이 Matrix를 함께 갱신한다.
- 아직 구현되지 않은 사용자별 기능 정책은 행을 미리 만들지 않는다. `UserFeaturePolicyProvider`가 실제로 도입될 때 범위를 확장한다.
