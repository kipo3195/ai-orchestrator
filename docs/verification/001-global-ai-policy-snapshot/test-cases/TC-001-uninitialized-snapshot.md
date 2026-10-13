# TC-001. 정책 초기 상태 점검

| 항목 | 값 |
|---|---|
| Verification | [Global AI Policy Snapshot Unit Verification](../README.md) |
| Test Level | Unit |

Required 여부, 자동화 상태와 `Last Verification Execution` 기준 실행 결과는 [상위 Verification 문서](../README.md)에서 관리한다. 이 문서의 `Last Execution`은 이 TC의 최근 실행을 기록하며, 단독 실행만으로 상위 Verification의 상태를 변경하지 않는다.

## Verification Purpose

최초 정상 refresh가 완료되기 전까지 명시적인 미초기화 snapshot을 유지하고, tenant의 Global AI Policy를 확인할 수 없는 요청에 대한 정책 평가가 `GLOBAL_POLICY_UNAVAILABLE` 사유의 거절 결정을 반환하는지 검증한다. 미초기화 snapshot에 정책 데이터나 로딩 시각을 포함할 수 없는 생성 불변식도 보조적으로 확인한다.

## Requirement Traceability

| 구분 | 참조 | 관련 요구사항 |
|---|---|---|
| Engineering | [Policy Immutable Snapshot Refresh — Initial State](../../../engineering/001-policy-immutable-snapshot-refresh.md#initial-state) | 애플리케이션 시작 시 `null`이 아닌 `initialized=false` snapshot을 사용하고, 최초 정상 refresh 전에는 AI 실행을 fail-closed 처리한다. |
| Engineering | [Policy Immutable Snapshot Refresh — Refresh Success and Failure](../../../engineering/001-policy-immutable-snapshot-refresh.md#refresh-success-and-failure) | 초기화 전 refresh가 실패하면 초기 snapshot을 유지한다. |
| Implementation | [GlobalAiPolicySnapshot](../../../../src/main/java/com/ucware/ai_orchestrator/policy/domain/GlobalAiPolicySnapshot.java) / [InMemoryGlobalAiPolicyStore](../../../../src/main/java/com/ucware/ai_orchestrator/policy/infrastructure/memory/InMemoryGlobalAiPolicyStore.java) | 정책 데이터와 로딩 시각이 없는 명시적인 초기 snapshot을 생성하고 현재 상태로 보관한다. |
| Implementation | [AiExecutionPolicyService](../../../../src/main/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyService.java) / [PolicySnapshotRefresher](../../../../src/main/java/com/ucware/ai_orchestrator/policy/infrastructure/scheduling/PolicySnapshotRefresher.java) | 미초기화 상태를 정책 사용 불가로 판단하고, 최초 scheduled refresh 실패 시 현재 snapshot을 교체하지 않는다. |

## Risk Addressed

- 최초 정상 refresh 전에 정책을 확인할 수 없는 상태에서 AI 실행이 잘못 허용되는 위험을 방지한다.
- 미초기화 상태가 일반적인 tenant 정책 OFF 상태와 구분되지 않으면 장애 원인과 정책 거절 원인을 정확히 식별하기 어렵다.
- 미초기화 snapshot에 정책 데이터나 로딩 시각이 포함되면 초기화 전 상태의 의미가 불명확해질 수 있다.
- 잘못된 허용이 발생하면 비활성화 대상 tenant에서 AI 실행이 수행되어 운영 정책을 위반하고 불필요한 AI 호출이 발생할 수 있다.

## Test Level and Boundary

- 테스트 수준: Unit
- 검증 대상: `GlobalAiPolicySnapshot`, `InMemoryGlobalAiPolicyStore`, `AiExecutionPolicyService`, `PolicySnapshotRefresher`
- 실제 사용 컴포넌트: Domain snapshot, in-memory store, 정책 평가 서비스와 refresher를 실제 객체로 사용한다.
- Test Double: 최초 refresh 실패 시나리오에서 `PolicySnapshotRepository`가 예외를 발생시키도록 Mock을 사용한다.
- 검증 제외: 실제 Policy DB, JDBC mapping, Spring `@Scheduled` 등록과 주기 실행, 전체 애플리케이션 시작 과정 및 실제 AI adapter 호출

### Rationale

초기 상태의 구조, 정책 평가 결과와 refresh 실패 후 상태는 외부 시스템 없이 객체의 반환값과 상태로 직접 관찰할 수 있으므로 단위 테스트로 핵심 위험을 검증할 수 있다. 실제 DB 장애 전파, Spring scheduling 설정과 AI 호출 차단의 전체 연결은 통합 또는 workflow 테스트가 필요한 별도 경계다.

## Preconditions — Given

1. 아직 정상 refresh가 한 번도 완료되지 않은 새 `InMemoryGlobalAiPolicyStore`가 있다.
2. 정책 평가 시 `GlobalAiPolicyProvider`는 미초기화 snapshot을 반환한다.
3. 최초 refresh 실패 시나리오에서 repository는 정책 조회 요청에 `IllegalStateException`을 발생시킨다.
4. 생성 불변식 검증에서는 `initialized=false`이면서 정책 데이터 또는 로딩 시각이 존재하는 입력을 사용한다.

## Test Data

| 항목 | 값 | 선택 이유 |
|---|---|---|
| Tenant ID | `tenant-1` | 미초기화 상태에서는 tenant별 정책과 무관하게 동일한 결과가 나와야 하므로 대표 식별자를 사용한다. |
| 초기 snapshot | `initialized=false`, 빈 `tenantPolicies`, `loadedAt=null` | 정책이 아직 한 번도 정상 로딩되지 않았음을 명시적으로 표현하는 Domain 초기 상태다. |
| Repository 실패 | `IllegalStateException("database unavailable")` | 최초 정책 조회 실패를 재현하고 scheduled 진입점의 실패 처리와 상태 유지를 확인한다. |
| Clock | `2026-01-01T00:00:00Z` 고정 | Refresher 생성에 필요한 시간을 결정적으로 제공한다. 실패 시에는 새 `loadedAt`이 게시되지 않아야 한다. |
| 잘못된 미초기화 상태 | `tenant-1=true` 또는 `loadedAt=2026-01-01T00:00:00Z` 포함 | 미초기화 snapshot이 정책 데이터와 로딩 시각을 가질 수 없다는 생성 불변식을 확인한다. |

## Procedure — When

1. 새 `InMemoryGlobalAiPolicyStore`에서 현재 snapshot을 조회한다.
2. 미초기화 snapshot을 제공하는 `AiExecutionPolicyService`에 `tenant-1`의 Global Policy 평가를 요청한다.
3. repository가 예외를 발생시키는 `PolicySnapshotRefresher.scheduledRefresh()`를 최초 refresh로 호출한다.
4. 실패 처리 후 Store의 현재 snapshot을 다시 조회한다.
5. 정책 데이터 또는 로딩 시각을 포함한 미초기화 snapshot 생성을 각각 시도한다.

## Expected Results — Then

1. Store의 최초 snapshot은 `null`이 아니며 `initialized=false`, 빈 `tenantPolicies`, `loadedAt=null`이다.
2. `tenant-1`의 정책 평가 결과는 `allowed=false`이다.
3. 정책 평가의 거절 사유는 일반적인 OFF 상태의 `GLOBAL_AI_DISABLED`가 아니라 `GLOBAL_POLICY_UNAVAILABLE`이다.
4. 최초 scheduled refresh의 repository 예외는 호출자에게 전파되지 않는다.
5. Refresh 실패 후 Store는 교체되지 않은 동일한 초기 snapshot을 유지하며 계속 `initialized=false`이다.
6. 정책 데이터를 포함한 미초기화 snapshot 생성은 `IllegalArgumentException`으로 거절된다.
7. 로딩 시각을 포함한 미초기화 snapshot 생성은 `IllegalArgumentException`으로 거절된다.

## Automated Test Mapping

| 테스트 클래스 | 테스트 메서드 | 검증하는 기대 결과 |
|---|---|---|
| [InMemoryGlobalAiPolicyStoreTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/memory/InMemoryGlobalAiPolicyStoreTests.java) | `startsWithUninitializedSnapshot` | Then 1 |
| [AiExecutionPolicyServiceTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyServiceTests.java) | `rejectsWhenSnapshotHasNotBeenInitialized` | Then 2, 3 |
| [PolicySnapshotRefresherTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/scheduling/PolicySnapshotRefresherTests.java) | `keepsUninitializedSnapshotWhenFirstScheduledRefreshFails` | Then 4, 5 |
| [GlobalAiPolicySnapshotTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/domain/GlobalAiPolicySnapshotTests.java) | `rejectsUninitializedSnapshotWithPolicyData` | Then 6 |
| [GlobalAiPolicySnapshotTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/domain/GlobalAiPolicySnapshotTests.java) | `rejectsUninitializedSnapshotWithLoadedAt` | Then 7 |

## Execution Command

```sh
./gradlew test \
  --tests 'com.ucware.ai_orchestrator.policy.infrastructure.memory.InMemoryGlobalAiPolicyStoreTests.startsWithUninitializedSnapshot' \
  --tests 'com.ucware.ai_orchestrator.policy.application.AiExecutionPolicyServiceTests.rejectsWhenSnapshotHasNotBeenInitialized' \
  --tests 'com.ucware.ai_orchestrator.policy.infrastructure.scheduling.PolicySnapshotRefresherTests.keepsUninitializedSnapshotWhenFirstScheduledRefreshFails' \
  --tests 'com.ucware.ai_orchestrator.policy.domain.GlobalAiPolicySnapshotTests.rejectsUninitializedSnapshotWithPolicyData' \
  --tests 'com.ucware.ai_orchestrator.policy.domain.GlobalAiPolicySnapshotTests.rejectsUninitializedSnapshotWithLoadedAt' \
  --rerun-tasks
```

## Last Execution

| 항목 | 값 |
|---|---|
| Executed At | 2026-10-13 17:41 KST |
| Commit | `2707097` |
| Working Tree | Clean |
| Environment | Java 17.0.19, Gradle 9.7.1 |
| Command | `./gradlew test --tests '*policy.*' --tests '*ConversationStartServiceTests' --rerun-tasks` |
| Execution Scope | Verification |
| Parent Execution / Evidence | [Last Verification Execution](../README.md#last-verification-execution) |
| Result | Passed |
| Passed / Failed / Skipped | 5 / 0 / 0 |

## Cleanup / Postconditions

- 외부 시스템이나 영속 데이터를 사용하지 않으므로 별도 정리가 필요하지 않다.
- 각 테스트가 생성한 객체와 Mock은 테스트 종료 후 폐기된다.

## Limitations

- 실제 Policy DB 연결 실패와 JDBC 예외 변환은 검증하지 않는다.
- Spring이 `@Scheduled` 메서드를 등록하고 설정된 주기에 호출하는지는 검증하지 않는다.
- 애플리케이션 프로세스가 최초 refresh 실패 후에도 정상 기동 상태를 유지하는지는 검증하지 않는다.
- 이 TC는 정책 평가 결과까지만 검증하며, 실제 AI port가 호출되지 않는 전체 실행 흐름은 별도 workflow 테스트 범위다.
