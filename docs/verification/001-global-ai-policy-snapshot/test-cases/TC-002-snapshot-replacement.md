# TC-002. Snapshot 전체 교체 및 불변성

| 항목 | 값 |
|---|---|
| Verification | [Global AI Policy Snapshot Unit Verification](../README.md) |
| Test Level | Unit |

Required 여부, 자동화 상태와 `Last Verification Execution` 기준 실행 결과는 [상위 Verification 문서](../README.md)에서 관리한다. 이 문서의 `Last Execution`은 이 TC의 최근 실행을 기록하며, 단독 실행만으로 상위 Verification의 상태를 변경하지 않는다.

## Verification Purpose

정상 refresh가 완료되면 기존 정책과 병합하지 않고 전체 tenant 정책을 포함한 새 immutable snapshot으로 교체하며, 조회 결과가 비어 있어도 초기화가 완료된 빈 snapshot으로 게시하는지 검증한다. 정상 snapshot을 보유한 상태에서 후속 repository 조회 또는 null 정책 값·빈 tenant ID에 의한 새 snapshot validation이 실패하면 마지막 정상 snapshot을 변경 없이 유지해야 한다.

## Requirement Traceability

| 구분 | 참조 | 관련 요구사항 |
|---|---|---|
| Engineering | [Policy Immutable Snapshot Refresh — Snapshot Model](../../../engineering/001-policy-immutable-snapshot-refresh.md#snapshot-model) | Snapshot 생성 후 내부 tenant policy collection이 변경되지 않아야 한다. |
| Engineering | [Policy Immutable Snapshot Refresh — Atomic Publication](../../../engineering/001-policy-immutable-snapshot-refresh.md#atomic-publication) | 전체 조회 결과로 만든 새 snapshot을 한 번에 게시하며, 기존 snapshot의 수정 또는 일부 정책의 병합을 허용하지 않는다. |
| Engineering | [Policy Immutable Snapshot Refresh — Refresh Success and Failure](../../../engineering/001-policy-immutable-snapshot-refresh.md#refresh-success-and-failure) | 정상 refresh는 전체 snapshot과 로딩 시각을 교체하고, 후속 refresh 실패는 마지막 정상 snapshot을 유지한다. |
| Engineering | [Policy Immutable Snapshot Refresh — Validation Before Publication](../../../engineering/001-policy-immutable-snapshot-refresh.md#validation-before-publication) | 새 snapshot의 validation이 게시 전에 완료되며, validation 실패 결과를 게시하지 않는다. |
| Decision | [Policy Persistence Access — Tenant Partitioning](../../../decisions/001-policy-persistence-access.md#tenant-partitioning) | Global Policy 전체를 tenant ID 기준 snapshot으로 게시하며, row가 없는 tenant는 OFF로 판단한다. |
| Implementation | [GlobalAiPolicySnapshot](../../../../src/main/java/com/ucware/ai_orchestrator/policy/domain/GlobalAiPolicySnapshot.java) | 입력 Map을 복사해 snapshot 내부 상태를 보호하고 tenant별 활성화 상태를 제공한다. |
| Implementation | [InMemoryGlobalAiPolicyStore](../../../../src/main/java/com/ucware/ai_orchestrator/policy/infrastructure/memory/InMemoryGlobalAiPolicyStore.java) / [PolicySnapshotRefresher](../../../../src/main/java/com/ucware/ai_orchestrator/policy/infrastructure/scheduling/PolicySnapshotRefresher.java) | 현재 snapshot reference를 새 전체 snapshot으로 교체하고, scheduled refresh 실패 시 기존 reference를 유지한다. |

## Risk Addressed

- 전체 교체 대신 기존 정책과 병합하면 source of truth에서 제거된 tenant가 snapshot에 남아 AI 실행이 계속 허용될 수 있다.
- 성공한 빈 정책 조회를 미초기화 상태로 유지하면 정상적인 전체 tenant OFF 상태를 정책 조회 불가 상태로 잘못 판단할 수 있다.
- Snapshot 생성에 사용한 Map 또는 snapshot이 노출한 collection을 외부에서 변경할 수 있으면 refresh와 무관하게 현재 정책이 바뀔 수 있다.
- 정상 snapshot을 보유한 상태에서 refresh 실패 결과로 현재 상태를 덮어쓰면 이미 확인된 마지막 정상 정책을 사용할 수 없게 된다.
- 필수 값이 null이거나 tenant ID가 빈 잘못된 정책 결과가 게시되면 마지막 정상 정책을 잃고 잘못된 허용 또는 차단 판단이 발생할 수 있다.
- 이러한 결함은 비활성화되거나 제거된 tenant의 AI 실행을 잘못 허용하거나, 정상 tenant의 실행을 예기치 않게 차단할 수 있다.

## Test Level and Boundary

- 테스트 수준: Unit
- 검증 대상: `GlobalAiPolicySnapshot`, `InMemoryGlobalAiPolicyStore`, `PolicySnapshotRefresher`
- 실제 사용 컴포넌트: Domain snapshot과 in-memory store, refresher를 실제 객체로 사용한다.
- Test Double: `PolicySnapshotRepository`가 순차적인 전체 정책 결과, 예외 또는 null 정책 값·빈 tenant ID를 포함한 잘못된 결과를 반환하도록 Mock을 사용하고, `Clock`은 결정적인 로딩 시각을 제공하도록 제어한다.
- 검증 제외: 실제 Policy DB와 JDBC row mapping, Spring `@Scheduled` 등록과 주기 실행, 여러 Orchestrator instance 사이의 동기화, 정책 조회와 갱신이 겹치는 동시성 동작

### Rationale

전체 조회 결과로 생성한 snapshot의 값과 객체 identity, 이전 snapshot의 상태, 외부 변경 시도와 refresh 실패 후 store 상태를 객체 수준에서 직접 관찰할 수 있으므로 단위 테스트로 교체 및 불변성의 핵심 위험을 결정적으로 검증할 수 있다. 실제 DB 조회 결과의 완전성은 JDBC 통합 테스트가 필요하고, 읽기와 교체가 동시에 수행될 때의 원자성은 TC-004에서 별도로 검증한다.

## Preconditions — Given

1. 첫 번째 정상 refresh 결과가 게시되어 initialized 상태인 `InMemoryGlobalAiPolicyStore`가 있다.
2. 후속 정상 refresh 결과는 기존 tenant의 상태 변경, 기존 결과에서 제거된 tenant와 새로 추가된 tenant를 포함한다.
3. 불변성 검증에는 테스트 코드에서 수정 가능한 원본 Map을 사용한다.
4. 실패 시나리오에서 repository는 정상 snapshot 게시 이후 `IllegalStateException`을 발생시킨다.
5. Validation 실패 시나리오에서 repository는 정상 snapshot 게시 이후 null 정책 값 또는 빈 tenant ID를 포함한 Map을 반환한다.
6. 빈 조회 성공 시나리오에서 repository는 빈 Map을 정상 반환한다.

## Test Data

| 항목 | 값 | 선택 이유 |
|---|---|---|
| 첫 번째 전체 정책 | `tenant-keep=true`, `tenant-remove=true` | 유지되는 tenant와 다음 refresh에서 제거될 tenant를 함께 표현한다. |
| 두 번째 전체 정책 | `tenant-keep=false`, `tenant-add=true` | 기존 값 변경, tenant 제거와 tenant 추가가 한 번의 전체 교체에 반영되는지 확인한다. |
| 빈 전체 정책 | `{}` | 정책 row가 없는 정상 조회를 미초기화 상태와 구분해 초기화 완료 및 전체 tenant OFF로 처리하는지 확인한다. |
| 첫 번째 로딩 시각 | `2026-01-01T00:00:00Z` | 이전 정상 snapshot의 로딩 시각을 식별하기 위한 고정값이다. |
| 두 번째 로딩 시각 | `2026-01-01T00:05:00Z` | 교체 후 새 snapshot의 로딩 시각이 갱신됐는지 구분하기 위한 고정값이다. |
| 외부 변경 | 원본 Map에 tenant 추가 또는 값 변경, 노출된 Map에 `put` 호출 | 방어적 복사와 읽기 전용 collection 노출을 각각 검증한다. |
| Repository 실패 | `IllegalStateException("database unavailable")` | 정상 로딩 이후 발생한 정책 조회 실패와 마지막 정상 snapshot 유지를 재현한다. |
| Validation 실패 결과 | `tenant-2=null` | 필수 정책 값이 null인 새 snapshot 생성 실패와 publication 방지를 재현한다. |
| Tenant ID validation 실패 결과 | `"  "=true` | 공백으로만 구성된 tenant ID의 snapshot 생성 실패와 publication 방지를 재현한다. |

## Procedure — When

### Scenario A — 전체 Snapshot 교체

1. Repository가 첫 번째 전체 정책을 반환하도록 설정하고 refresh를 실행한다.
2. Store의 현재 snapshot을 이전 snapshot으로 보관한다.
3. Repository가 두 번째 전체 정책을 반환하도록 변경하고 다시 refresh를 실행한다.
4. Store에서 교체된 현재 snapshot을 조회한다.

### Scenario B — Snapshot 불변성

1. 수정 가능한 원본 Map으로 initialized snapshot을 생성한다.
2. Snapshot 생성 후 원본 Map의 값을 변경하고 tenant를 추가한다.
3. `snapshot.tenantPolicies()`가 반환한 Map에 `put`을 시도한다.

### Scenario C — 마지막 정상 Snapshot 유지

1. Repository의 정상 결과로 scheduled refresh를 실행하고 현재 snapshot을 보관한다.
2. 다음 repository 조회에서 예외가 발생하도록 설정한다.
3. `scheduledRefresh()`를 다시 호출하고 Store의 현재 snapshot을 조회한다.

### Scenario D — Validation 실패 결과 Publication 방지

1. Repository의 정상 결과로 scheduled refresh를 실행하고 현재 snapshot을 보관한다.
2. 다음 repository 조회가 null 정책 값 또는 빈 tenant ID를 포함한 Map을 반환하도록 설정한다.
3. `scheduledRefresh()`를 다시 호출하고 Store의 현재 snapshot을 조회한다.

### Scenario E — 빈 정책 조회 성공

1. Repository가 빈 Map을 정상 반환하도록 설정한다.
2. 초기화되지 않은 Store를 대상으로 refresh를 실행한다.
3. Store의 현재 snapshot과 임의 tenant의 정책 상태를 조회한다.

## Expected Results — Then

1. 첫 번째 refresh 결과는 `initialized=true`이며 첫 번째 전체 정책과 첫 번째 로딩 시각을 포함한다.
2. 두 번째 refresh 후 현재 snapshot은 이전 snapshot과 다른 instance이며, 두 번째 전체 정책과 두 번째 로딩 시각을 포함한다.
3. 두 번째 결과에서 제거된 `tenant-remove`는 현재 snapshot에 남지 않고 OFF로 판단되며, 새 `tenant-add`와 변경된 `tenant-keep` 상태가 반영된다.
4. 교체 전에 보관한 이전 snapshot의 정책과 로딩 시각은 두 번째 refresh 이후에도 변경되지 않는다.
5. Snapshot 생성 후 원본 Map을 변경해도 snapshot의 tenant 정책은 변경되지 않는다.
6. `snapshot.tenantPolicies()`가 반환한 Map의 변경 시도는 `UnsupportedOperationException`으로 거절된다.
7. 정상 snapshot 보유 후 scheduled refresh 실패는 호출자에게 예외를 전파하지 않는다.
8. 실패 후 Store는 교체되지 않은 동일한 마지막 정상 snapshot instance를 유지하며 정책과 로딩 시각도 변경되지 않는다.
9. 새 snapshot validation 실패는 scheduled refresh 호출자에게 예외를 전파하지 않는다.
10. Validation 실패 후 Store는 교체되지 않은 동일한 마지막 정상 snapshot instance를 유지하며 정책과 로딩 시각도 변경되지 않는다.
11. 빈 정책 조회 성공 후 snapshot은 `initialized=true`이고 tenant 정책은 비어 있다.
12. 빈 정책 조회 성공 시에도 정상 로딩 시각이 설정된다.
13. 빈 정책 조회 결과에서 임의 tenant는 OFF로 판단된다.

## Automated Test Mapping

| 테스트 클래스 | 테스트 메서드 | 검증하는 기대 결과 |
|---|---|---|
| [PolicySnapshotRefresherTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/scheduling/PolicySnapshotRefresherTests.java) | `replacesSnapshotWithAllTenantPolicies` | Then 1의 최초 정상 snapshot 게시와 전체 정책 조회 결과 반영 |
| [PolicySnapshotRefresherTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/scheduling/PolicySnapshotRefresherTests.java) | `publishesInitializedEmptySnapshotWhenRepositoryReturnsNoPolicies` | Then 11, 12, 13의 빈 조회 성공과 초기화 완료 및 전체 tenant OFF 처리 |
| [PolicySnapshotRefresherTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/scheduling/PolicySnapshotRefresherTests.java) | `replacesPreviousSnapshotWithoutMergingRemovedTenants` | Then 2, 3, 4 |
| [PolicySnapshotRefresherTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/scheduling/PolicySnapshotRefresherTests.java) | `keepsLastValidSnapshotWhenScheduledRefreshFails` | Then 7, 8의 마지막 정상 snapshot instance 및 정책 유지 |
| [PolicySnapshotRefresherTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/scheduling/PolicySnapshotRefresherTests.java) | `keepsLastValidSnapshotWhenNewSnapshotValidationFails` | Then 9, 10의 validation 실패 결과 publication 방지 및 마지막 정상 snapshot 유지 |
| [PolicySnapshotRefresherTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/scheduling/PolicySnapshotRefresherTests.java) | `keepsLastValidSnapshotWhenTenantIdValidationFails` | Then 9, 10의 빈 tenant ID publication 방지 및 마지막 정상 snapshot 유지 |
| [GlobalAiPolicySnapshotTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/domain/GlobalAiPolicySnapshotTests.java) | `copiesTenantPoliciesDefensively` | Then 5 |
| [GlobalAiPolicySnapshotTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/domain/GlobalAiPolicySnapshotTests.java) | `exposesUnmodifiableTenantPolicies` | Then 6 |
| [GlobalAiPolicySnapshotTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/domain/GlobalAiPolicySnapshotTests.java) | `rejectsNullTenantId`, `rejectsBlankTenantId` | Then 9의 tenant ID 생성 불변식 검증 |

## Execution Command

TC-002의 모든 자동화 테스트는 다음 명령으로 실행한다.

```sh
./gradlew test \
  --tests 'com.ucware.ai_orchestrator.policy.infrastructure.scheduling.PolicySnapshotRefresherTests.replacesSnapshotWithAllTenantPolicies' \
  --tests 'com.ucware.ai_orchestrator.policy.infrastructure.scheduling.PolicySnapshotRefresherTests.publishesInitializedEmptySnapshotWhenRepositoryReturnsNoPolicies' \
  --tests 'com.ucware.ai_orchestrator.policy.infrastructure.scheduling.PolicySnapshotRefresherTests.replacesPreviousSnapshotWithoutMergingRemovedTenants' \
  --tests 'com.ucware.ai_orchestrator.policy.infrastructure.scheduling.PolicySnapshotRefresherTests.keepsLastValidSnapshotWhenScheduledRefreshFails' \
  --tests 'com.ucware.ai_orchestrator.policy.infrastructure.scheduling.PolicySnapshotRefresherTests.keepsLastValidSnapshotWhenNewSnapshotValidationFails' \
  --tests 'com.ucware.ai_orchestrator.policy.infrastructure.scheduling.PolicySnapshotRefresherTests.keepsLastValidSnapshotWhenTenantIdValidationFails' \
  --tests 'com.ucware.ai_orchestrator.policy.domain.GlobalAiPolicySnapshotTests.copiesTenantPoliciesDefensively' \
  --tests 'com.ucware.ai_orchestrator.policy.domain.GlobalAiPolicySnapshotTests.exposesUnmodifiableTenantPolicies' \
  --tests 'com.ucware.ai_orchestrator.policy.domain.GlobalAiPolicySnapshotTests.rejectsNullTenantId' \
  --tests 'com.ucware.ai_orchestrator.policy.domain.GlobalAiPolicySnapshotTests.rejectsBlankTenantId' \
  --rerun-tasks
```

## Last Execution

| 항목 | 값 |
|---|---|
| Executed At | 2026-10-13 17:24 KST |
| Commit | `9980610` |
| Working Tree | Dirty |
| Environment | Java 17.0.19, Gradle 9.7.1 |
| Command | `./gradlew test --tests '*policy.*' --tests '*ConversationStartServiceTests' --rerun-tasks` |
| Execution Scope | Verification |
| Parent Execution / Evidence | [Last Verification Execution](../README.md#last-verification-execution) |
| Result | Passed |
| Passed / Failed / Skipped | 10 / 0 / 0 |

## Cleanup / Postconditions

- 외부 시스템이나 영속 데이터를 사용하지 않으므로 별도 정리가 필요하지 않다.
- 각 테스트가 생성한 snapshot, store, 수정 가능한 Map과 Mock은 테스트 종료 후 폐기된다.

## Limitations

- 실제 DB의 전체 조회, SQL 및 JDBC row mapping과 중복·빈 tenant·`enabled=NULL` 검증은 확인하지 않는다.
- Spring이 `@Scheduled` 메서드를 등록하고 설정된 주기에 호출하는지는 검증하지 않는다.
- 동시 reader가 교체 전후의 완전한 snapshot만 관찰하는지는 TC-004의 범위다.
