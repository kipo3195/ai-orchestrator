# TC-003. Global/Feature 평가

| 항목 | 값 |
|---|---|
| Verification | [AI Execution Policy Chain Verification](../README.md) |
| Test Level | Unit |

## Verification Purpose

사용자 정보가 없는 AI 실행에서 Global → Feature 순서로 평가하고, 두 정책이 모두 ON일 때만 허용하며 User Policy 조회는 수행하지 않는지 검증한다.

## Requirement Traceability

| 구분 | 참조 | 관련 요구사항 |
|---|---|---|
| Engineering | [Policy Immutable Snapshot Refresh — Atomic Publication](../../../engineering/001-policy-immutable-snapshot-refresh.md#atomic-publication) | 하나의 요청은 동일한 snapshot으로 Global과 Feature를 판단한다. |
| Decision | [Policy Persistence Access — Tenant Partitioning](../../../decisions/001-policy-persistence-access.md#tenant-partitioning) | 정책 row가 없으면 암묵적인 ON을 적용하지 않는다. |
| Implementation | [AiExecutionPolicyService](../../../../src/main/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyService.java) | `evaluateFeature(...)`에서 Global과 Feature 결정 및 입력 검증을 수행한다. |

## Risk Addressed

- 누락된 Global 또는 Feature 정책이 암묵적으로 허용될 수 있다.
- Feature가 Global 정책을 우회하거나 잘못된 순서로 평가될 수 있다.
- 사용자 없는 실행이 불필요한 Redis/DB User 조회에 의존할 수 있다.
- 잘못된 식별자가 정상적인 정책 누락으로 숨겨질 수 있다.

## Test Level and Boundary

- 테스트 수준: Unit
- 검증 대상: `AiExecutionPolicyService.evaluateFeature(...)`와 `AiPolicySnapshot`의 기본 OFF 의미
- 실제 사용 컴포넌트: `AiExecutionPolicyService`, immutable snapshot
- Test Double: 고정 snapshot을 반환하는 `AiPolicyProvider` Stub, 미호출 관찰용 `UserAiPolicyProvider` Mock
- 검증 제외: JDBC 조회, refresh scheduler와 실제 AI workflow

### Rationale

정책 조합과 결정 사유는 입력 snapshot과 반환 결정, User Provider 미호출만으로 완전히 관찰할 수 있어 단위 테스트가 가장 직접적이다.

## Preconditions — Given

1. 초기화된 snapshot에 Global과 Feature가 ON, OFF 또는 누락된 조합이 있다.
2. 사용자 없는 평가를 위해 `evaluateFeature(...)`를 사용한다.

## Test Data

| 항목 | 값 | 선택 이유 |
|---|---|---|
| Tenant | `tenant-1` | 대표 유효 tenant |
| Missing tenant | snapshot에 row 없음 | 기본 OFF 확인 |
| Feature | `CONVERSATION_START` | 현재 지원하는 실제 Feature |
| Invalid tenant | 공백 문자열 | 입력 검증 경계 |
| Invalid feature | `null` | 필수 Feature 검증 경계 |

## Procedure — When

1. Global OFF 또는 누락인 snapshot에서 평가한다.
2. Global ON이고 Feature OFF 또는 누락인 snapshot에서 평가한다.
3. Global과 Feature가 모두 ON인 snapshot에서 평가한다.
4. 잘못된 tenant 또는 feature로 평가한다.

## Expected Results — Then

1. Global OFF 또는 누락은 `GLOBAL_AI_DISABLED`로 거절된다.
2. Global ON 이후 Feature OFF 또는 누락은 `FEATURE_AI_DISABLED`로 거절된다.
3. Global과 Feature가 모두 ON이면 `ALLOWED`다.
4. 모든 사용자 없는 평가에서 User Provider는 호출되지 않는다.
5. 잘못된 식별자는 Provider 조회 전에 입력 오류로 거절된다.

## Review

| 리뷰 | 상태 | Reviewed At | Reviewed Commit | Notes |
|---|---|---|---|---|
| Scenario | Approved | 2026-10-28 | Working tree (base `9aae7ed`) | 차단 이슈 없음 |
| Assertion | Approved | 2026-10-28 | Working tree (base `9aae7ed`) | 모든 Expected Results가 실제 assertion에 매핑되며 차단 이슈 없음 |

## Automated Test Mapping

| 테스트 클래스 | 테스트 메서드 | 검증하는 기대 결과 |
|---|---|---|
| [AiExecutionPolicyServiceTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyServiceTests.java) | `rejectsWhenGlobalPolicyIsDisabled`, `missingGlobalPolicyDefaultsToDisabled` | Then 1 |
| [AiExecutionPolicyServiceTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyServiceTests.java) | `rejectsWhenFeaturePolicyIsDisabledOrMissing` | Then 2 |
| [AiExecutionPolicyServiceTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyServiceTests.java) | `evaluatesGlobalAndFeatureWithoutResolvingUserPolicy` | Then 3, 4 |
| [AiExecutionPolicyServiceTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyServiceTests.java) | `rejectsInvalidInputsBeforeReadingProviders` | Then 5 |
| [AiPolicySnapshotTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/domain/AiPolicySnapshotTests.java) | `missingPoliciesDefaultToDisabled` | Then 1, 2의 snapshot 기본값 |

## Execution Command

```sh
./gradlew test \
  --tests 'com.ucware.ai_orchestrator.policy.application.AiExecutionPolicyServiceTests.rejectsWhenGlobalPolicyIsDisabled' \
  --tests 'com.ucware.ai_orchestrator.policy.application.AiExecutionPolicyServiceTests.missingGlobalPolicyDefaultsToDisabled' \
  --tests 'com.ucware.ai_orchestrator.policy.application.AiExecutionPolicyServiceTests.rejectsWhenFeaturePolicyIsDisabledOrMissing' \
  --tests 'com.ucware.ai_orchestrator.policy.application.AiExecutionPolicyServiceTests.evaluatesGlobalAndFeatureWithoutResolvingUserPolicy' \
  --tests 'com.ucware.ai_orchestrator.policy.application.AiExecutionPolicyServiceTests.rejectsInvalidInputsBeforeReadingProviders' \
  --tests 'com.ucware.ai_orchestrator.policy.domain.AiPolicySnapshotTests.missingPoliciesDefaultToDisabled' \
  --rerun-tasks
```

## Execution Evidence

이 TC의 실행 결과는 상위 Verification의 최신 전체 실행 증적에서 관리한다. TC 문서에는 실행 메타데이터를 중복 기록하지 않는다.

- Latest Verification Execution: [상위 Verification 문서](../README.md#last-verification-execution)
- TC 결과 확인: 연결된 execution evidence에서 `Automated Test Mapping`에 해당하는 테스트 결과를 확인한다.

## Cleanup / Postconditions

- 외부 시스템과 영속 데이터를 사용하지 않는다.
- Snapshot과 Mock은 각 테스트 종료 후 폐기된다.

## Limitations

- 현재 실제 Feature 값은 `CONVERSATION_START` 하나다.
