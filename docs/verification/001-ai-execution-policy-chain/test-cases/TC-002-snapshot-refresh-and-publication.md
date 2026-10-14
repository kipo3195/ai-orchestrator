# TC-002. Snapshot refresh와 게시

| 항목 | 값 |
|---|---|
| Verification | [AI Execution Policy Chain Verification](../README.md) |
| Test Level | Unit / Integration |

Required 여부, 자동화 상태와 `Last Verification Execution` 기준 실행 결과는 [상위 Verification 문서](../README.md)에서 관리한다. 이 문서의 `Last Execution`은 해당 TC의 최근 단독 또는 전체 실행을 기록하며, 단독 실행만으로 상위 Verification의 상태를 변경하지 않는다.

## Verification Purpose

Global과 Feature Policy 전체가 하나의 불변 snapshot으로 게시되고, 새 refresh가 이전 값을 병합하지 않으며, 조회 또는 validation 실패 시 마지막 정상 snapshot을 유지하는지 검증한다.

## Requirement Traceability

| 구분 | 참조 | 관련 요구사항 |
|---|---|---|
| Engineering | [Policy Immutable Snapshot Refresh — Atomic Publication](../../../engineering/001-policy-immutable-snapshot-refresh.md#atomic-publication) | Global/Feature 전체를 검증한 뒤 하나의 snapshot으로 교체한다. |
| Engineering | [Policy Immutable Snapshot Refresh — Refresh Success and Failure](../../../engineering/001-policy-immutable-snapshot-refresh.md#refresh-success-and-failure) | 실패 시 현재 snapshot을 변경하지 않는다. |
| Decision | [Policy Persistence Access](../../../decisions/001-policy-persistence-access.md) | Global/Feature 전체 조회는 JDBC adapter가 담당한다. |
| Implementation | [PolicySnapshotRefresher](../../../../src/main/java/com/ucware/ai_orchestrator/policy/infrastructure/scheduling/PolicySnapshotRefresher.java) / [JdbcPolicySnapshotRepository](../../../../src/main/java/com/ucware/ai_orchestrator/policy/infrastructure/persistence/JdbcPolicySnapshotRepository.java) | 조회 결과로 snapshot을 생성하고 H2 schema를 통해 row mapping을 수행한다. |

## Risk Addressed

- Global과 Feature가 서로 다른 refresh 결과에서 조합되어 잘못된 결정을 내릴 수 있다.
- 삭제된 정책이 이전 snapshot과 병합되어 계속 적용될 수 있다.
- Repository 또는 validation 실패가 마지막 정상 정책을 덮어쓸 수 있다.
- 호출자가 snapshot 내부 Map을 변경하여 동시 요청 결과에 영향을 줄 수 있다.

## Test Level and Boundary

- 테스트 수준: Domain/Application Unit 및 H2 JDBC Integration Slice
- 검증 대상: `AiPolicySnapshot`, `PolicySnapshotRefresher`, `JdbcPolicySnapshotRepository`
- 실제 사용 컴포넌트: snapshot과 refresher, H2 schema, Spring `JdbcClient`
- Test Double: refresh 성공·실패 결과를 제어하는 `PolicySnapshotRepository` Mock과 고정 `Clock`
- 검증 제외: 운영 DB 제품, 실제 scheduler 시간 경과와 다중 instance refresh

### Rationale

불변성과 교체 의미는 단위 테스트로 검증하고, SQL과 row mapping은 H2 JDBC slice로 확인한다. 운영 DB의 isolation과 driver 차이는 별도 통합 경계로 남긴다.

## Preconditions — Given

1. Global과 Feature Policy를 함께 반환하는 repository가 있다.
2. 이전 정상 snapshot이 게시된 경우가 있다.
3. Repository 예외 또는 null 정책 값을 반환하는 실패 경우가 있다.

## Test Data

| 항목 | 값 | 선택 이유 |
|---|---|---|
| Global | `tenant-1=true` | 명시적으로 허용된 대표 tenant |
| Feature | `(tenant-1, conversation-start)=true` | Global과 함께 게시되는 대표 Feature |
| 두 번째 refresh | `tenant-2=true`, Feature 없음 | 기존 정책이 병합되지 않는지 식별 |
| Loaded At | `2026-01-01T00:00:00Z`, `00:05:00Z` | snapshot 교체 시점을 구분 |
| 잘못된 값 | Feature enabled `null` | publication 전 validation 실패 재현 |

## Procedure — When

1. 정상 Global/Feature 결과로 refresh한다.
2. 빈 repository 결과와 변경된 전체 결과로 각각 refresh한다.
3. 정상 snapshot 게시 후 repository 또는 validation 실패를 발생시킨다.
4. Snapshot 생성 원본 Map과 접근자가 반환한 Map을 변경한다.
5. H2에 Global과 Feature row를 삽입한 뒤 JDBC repository를 조회한다.

## Expected Results — Then

1. Global과 Feature가 동일한 `loadedAt`을 가진 하나의 initialized snapshot으로 게시된다.
2. 빈 결과도 정상적인 initialized 빈 snapshot으로 게시된다.
3. 두 번째 refresh는 첫 번째 정책을 병합하지 않고 전체 교체한다.
4. Repository 또는 validation 실패 후 마지막 정상 snapshot reference를 유지한다.
5. Snapshot은 입력 Map을 방어적으로 복사하고 변경 불가능하게 노출한다.
6. JDBC adapter는 Global row와 `feature_key`를 올바른 Domain key로 변환한다.

## Automated Test Mapping

| 테스트 클래스 | 테스트 메서드 | 검증하는 기대 결과 |
|---|---|---|
| [PolicySnapshotRefresherTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/scheduling/PolicySnapshotRefresherTests.java) | `publishesGlobalAndFeaturePoliciesInOneSnapshot` | Then 1 |
| [PolicySnapshotRefresherTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/scheduling/PolicySnapshotRefresherTests.java) | `publishesInitializedEmptySnapshotWhenRepositoryReturnsNoPolicies` | Then 2 |
| [PolicySnapshotRefresherTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/scheduling/PolicySnapshotRefresherTests.java) | `replacesWholeSnapshotWithoutMergingRemovedPolicies` | Then 3 |
| [PolicySnapshotRefresherTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/scheduling/PolicySnapshotRefresherTests.java) | `keepsLastValidSnapshotWhenScheduledRefreshFails`, `keepsLastValidSnapshotWhenNewSnapshotValidationFails` | Then 4 |
| [AiPolicySnapshotTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/domain/AiPolicySnapshotTests.java) | `copiesGlobalAndFeaturePoliciesDefensively`, `validatesGlobalAndFeatureEntries` | Then 5 및 publication validation |
| [JdbcPolicySnapshotRepositoryTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/persistence/JdbcPolicySnapshotRepositoryTests.java) | `loadsGlobalAndFeaturePoliciesTogether`, `returnsEmptyCollectionsWhenPoliciesDoNotExist` | Then 6 및 빈 DB 결과 |

## Execution Command

```sh
./gradlew test \
  --tests 'com.ucware.ai_orchestrator.policy.infrastructure.scheduling.PolicySnapshotRefresherTests.publishesGlobalAndFeaturePoliciesInOneSnapshot' \
  --tests 'com.ucware.ai_orchestrator.policy.infrastructure.scheduling.PolicySnapshotRefresherTests.publishesInitializedEmptySnapshotWhenRepositoryReturnsNoPolicies' \
  --tests 'com.ucware.ai_orchestrator.policy.infrastructure.scheduling.PolicySnapshotRefresherTests.replacesWholeSnapshotWithoutMergingRemovedPolicies' \
  --tests 'com.ucware.ai_orchestrator.policy.infrastructure.scheduling.PolicySnapshotRefresherTests.keepsLastValidSnapshotWhenScheduledRefreshFails' \
  --tests 'com.ucware.ai_orchestrator.policy.infrastructure.scheduling.PolicySnapshotRefresherTests.keepsLastValidSnapshotWhenNewSnapshotValidationFails' \
  --tests 'com.ucware.ai_orchestrator.policy.domain.AiPolicySnapshotTests.copiesGlobalAndFeaturePoliciesDefensively' \
  --tests 'com.ucware.ai_orchestrator.policy.domain.AiPolicySnapshotTests.validatesGlobalAndFeatureEntries' \
  --tests 'com.ucware.ai_orchestrator.policy.infrastructure.persistence.JdbcPolicySnapshotRepositoryTests' \
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
| Passed / Failed / Skipped | 9 / 0 / 0 |

## Cleanup / Postconditions

- `@JdbcTest` transaction rollback로 H2 test row를 정리한다.
- Unit test의 snapshot과 Mock은 테스트 종료 후 폐기된다.

## Limitations

- H2 이외 운영 DB의 transaction isolation, boolean mapping과 feature key 호환성은 보장하지 않는다.
- Dirty working tree 실행이므로 상위 Verification 완료 판정 evidence로 사용하지 않는다.
