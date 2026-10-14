# TC-001. Snapshot 초기 상태

| 항목 | 값 |
|---|---|
| Verification | [AI Execution Policy Chain Verification](../README.md) |
| Test Level | Unit |

Required 여부, 자동화 상태와 `Last Verification Execution` 기준 실행 결과는 [상위 Verification 문서](../README.md)에서 관리한다. 이 문서의 `Last Execution`은 해당 TC의 최근 단독 또는 전체 실행을 기록하며, 단독 실행만으로 상위 Verification의 상태를 변경하지 않는다.

## Verification Purpose

최초 정상 refresh 전에는 정책 데이터가 없는 명시적인 미초기화 snapshot을 사용하고, 이 상태의 AI 정책 평가가 `GLOBAL_POLICY_UNAVAILABLE`로 거절되는지 검증한다.

## Requirement Traceability

| 구분 | 참조 | 관련 요구사항 |
|---|---|---|
| Engineering | [Policy Immutable Snapshot Refresh — Initial State](../../../engineering/001-policy-immutable-snapshot-refresh.md#initial-state) | 최초 정상 refresh 전에는 `initialized=false` 상태로 fail-closed 처리한다. |
| Implementation | [AiPolicySnapshot](../../../../src/main/java/com/ucware/ai_orchestrator/policy/domain/AiPolicySnapshot.java) | 정책 데이터와 로딩 시각이 없는 초기 상태 불변식을 제공한다. |
| Implementation | [InMemoryAiPolicyStore](../../../../src/main/java/com/ucware/ai_orchestrator/policy/infrastructure/memory/InMemoryAiPolicyStore.java) / [AiExecutionPolicyService](../../../../src/main/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyService.java) | 초기 snapshot 보관과 미초기화 평가 거절을 담당한다. |

## Risk Addressed

- 애플리케이션 시작 직후 정책을 확인하지 못한 요청이 잘못 허용될 수 있다.
- 미초기화 상태와 정상적인 정책 OFF가 구분되지 않으면 운영 원인과 결정 사유가 왜곡될 수 있다.
- 미초기화 snapshot에 일부 정책 데이터가 포함되면 초기 상태의 의미가 불명확해질 수 있다.

## Test Level and Boundary

- 테스트 수준: Unit
- 검증 대상: `AiPolicySnapshot`, `InMemoryAiPolicyStore`, `AiExecutionPolicyService`, `PolicySnapshotRefresher`
- 실제 사용 컴포넌트: Domain snapshot, in-memory store, 정책 평가 서비스와 refresher
- Test Double: 최초 refresh 실패를 만드는 `PolicySnapshotRepository` Mock, User 조회 여부를 관찰하는 `UserAiPolicyProvider` Mock
- 검증 제외: 실제 DB, Spring scheduler 등록과 애플리케이션 startup lifecycle

### Rationale

초기 상태와 fail-closed 결과는 객체 상태, 정책 결정과 협력 객체 미호출로 직접 관찰할 수 있으므로 단위 테스트로 핵심 위험을 결정적으로 검증할 수 있다.

## Preconditions — Given

1. 정상 refresh가 한 번도 완료되지 않은 새 `InMemoryAiPolicyStore`가 있다.
2. 정책 서비스는 `AiPolicySnapshot.uninitialized()`를 반환하는 Provider를 사용한다.
3. 최초 refresh 실패 시 repository는 `IllegalStateException`을 발생시킨다.

## Test Data

| 항목 | 값 | 선택 이유 |
|---|---|---|
| Tenant ID | `tenant-1` | 초기화 전에는 tenant와 무관하게 동일 결과가 나와야 하는 대표값 |
| Feature | `CONVERSATION_START` | 현재 지원하는 실제 Feature |
| User ID | `user-1` | User Provider 미호출을 확인할 대표 사용자 |
| 초기 snapshot | 빈 Global/Feature Map, `loadedAt=null` | 정상 로딩 전 상태를 명시적으로 표현 |
| Repository 실패 | `IllegalStateException("database unavailable")` | 최초 DB 조회 실패 재현 |

## Procedure — When

1. 새 Store에서 현재 snapshot을 조회한다.
2. 미초기화 snapshot으로 `evaluateFeatureForUser(...)`를 호출한다.
3. repository가 실패하는 `scheduledRefresh()`를 실행한다.
4. 정책 데이터 또는 `loadedAt`이 포함된 미초기화 snapshot 생성을 시도한다.

## Expected Results — Then

1. 최초 snapshot은 `initialized=false`, 빈 Global/Feature Map, `loadedAt=null`이다.
2. 정책 결정은 `allowed=false`, `GLOBAL_POLICY_UNAVAILABLE`이다.
3. User Provider는 호출되지 않는다.
4. 최초 refresh 실패는 호출자에게 전파되지 않고 동일한 초기 snapshot을 유지한다.
5. 정책 데이터나 `loadedAt`을 가진 미초기화 snapshot 생성은 거절된다.

## Automated Test Mapping

| 테스트 클래스 | 테스트 메서드 | 검증하는 기대 결과 |
|---|---|---|
| [InMemoryAiPolicyStoreTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/memory/InMemoryAiPolicyStoreTests.java) | `startsWithUninitializedSnapshot` | Then 1 |
| [AiExecutionPolicyServiceTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyServiceTests.java) | `rejectsWhenSnapshotHasNotBeenInitialized` | Then 2, 3 |
| [PolicySnapshotRefresherTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/scheduling/PolicySnapshotRefresherTests.java) | `keepsUninitializedSnapshotWhenFirstScheduledRefreshFails` | Then 4 |
| [AiPolicySnapshotTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/domain/AiPolicySnapshotTests.java) | `rejectsUninitializedSnapshotWithAnyPolicyDataOrLoadedAt` | Then 5 |

## Execution Command

```sh
./gradlew test \
  --tests 'com.ucware.ai_orchestrator.policy.infrastructure.memory.InMemoryAiPolicyStoreTests.startsWithUninitializedSnapshot' \
  --tests 'com.ucware.ai_orchestrator.policy.application.AiExecutionPolicyServiceTests.rejectsWhenSnapshotHasNotBeenInitialized' \
  --tests 'com.ucware.ai_orchestrator.policy.infrastructure.scheduling.PolicySnapshotRefresherTests.keepsUninitializedSnapshotWhenFirstScheduledRefreshFails' \
  --tests 'com.ucware.ai_orchestrator.policy.domain.AiPolicySnapshotTests.rejectsUninitializedSnapshotWithAnyPolicyDataOrLoadedAt' \
  --rerun-tasks
```

## Last Execution

| 항목 | 값 |
|---|---|
| Executed At | 2026-10-14 17:03 KST |
| Commit | `0c85a21` + uncommitted changes |
| Working Tree | Dirty |
| Environment | Java 17.0.19, Gradle 9.7.1 |
| Command | 상위 Verification Tests 명령 |
| Execution Scope | Verification preliminary |
| Parent Execution / Evidence | `build/reports/tests/test/index.html` |
| Result | Passed |
| Passed / Failed / Skipped | 4 / 0 / 0 |

## Cleanup / Postconditions

- 외부 시스템이나 영속 데이터를 사용하지 않으므로 별도 정리가 필요하지 않다.
- 테스트별 snapshot, Store와 Mock은 테스트 종료 후 폐기된다.

## Limitations

- 실제 Spring lifecycle에서 최초 scheduled refresh가 호출되는 시점은 검증하지 않는다.
- Dirty working tree 실행이므로 상위 Verification 완료 판정 evidence로 사용하지 않는다.
