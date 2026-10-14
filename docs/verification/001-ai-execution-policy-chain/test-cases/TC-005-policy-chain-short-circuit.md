# TC-005. 정책 체인 short-circuit

| 항목 | 값 |
|---|---|
| Verification | [AI Execution Policy Chain Verification](../README.md) |
| Test Level | Unit |

Required 여부, 자동화 상태와 `Last Verification Execution` 기준 실행 결과는 [상위 Verification 문서](../README.md)에서 관리한다. 이 문서의 `Last Execution`은 해당 TC의 최근 단독 또는 전체 실행을 기록하며, 단독 실행만으로 상위 Verification의 상태를 변경하지 않는다.

## Verification Purpose

사용자 기반 평가가 Global → Feature → 전체 User Policy 순서를 지키고, 상위 정책 거절 시 User Provider를 호출하지 않으며, User 상태를 구체적인 정책 결정 사유로 변환하는지 검증한다.

## Requirement Traceability

| 구분 | 참조 | 관련 요구사항 |
|---|---|---|
| Engineering | [User Policy Cache-Aside Implementation — Policy Evaluation](../../../engineering/002-user-policy-cache-aside-implementation.md#policy-evaluation) | Global/Feature 허용 후 전체 User Policy를 평가하고 상태별 사유로 변환한다. |
| Engineering | [Policy Immutable Snapshot Refresh — Atomic Publication](../../../engineering/001-policy-immutable-snapshot-refresh.md#atomic-publication) | Global과 Feature는 하나의 snapshot에서 순서대로 평가한다. |
| Implementation | [AiExecutionPolicyService](../../../../src/main/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyService.java) | 평가 순서, short-circuit와 User resolution 변환을 담당한다. |

## Risk Addressed

- Global 또는 Feature가 OFF인데 Redis/DB User 조회가 수행될 수 있다.
- 기능별 application flow가 정책 평가 순서를 서로 다르게 조합할 수 있다.
- User의 명시적 OFF, 미설정과 unavailable이 하나의 모호한 거절 사유로 손실될 수 있다.
- 모든 정책이 ON인 정상 요청이 잘못 거절될 수 있다.

## Test Level and Boundary

- 테스트 수준: Unit
- 검증 대상: `AiExecutionPolicyService.evaluateFeatureForUser(...)`
- 실제 사용 컴포넌트: 정책 서비스와 immutable snapshot
- Test Double: snapshot Provider Stub과 상태·호출 여부를 제어하는 `UserAiPolicyProvider` Mock
- 검증 제외: Redis/DB adapter 내부 동작과 실제 AI workflow

### Rationale

평가 순서와 short-circuit는 반환 결정과 User Provider interaction으로 직접 관찰할 수 있으므로 빠르고 결정적인 단위 테스트가 적합하다.

## Preconditions — Given

1. Snapshot이 미초기화이거나 Global/Feature가 각각 ON/OFF인 경우가 있다.
2. User resolution은 `ENABLED`, `DISABLED`, `NOT_CONFIGURED` 또는 `Unavailable`이다.

## Test Data

| 항목 | 값 | 선택 이유 |
|---|---|---|
| Tenant | `tenant-1` | 대표 tenant |
| Feature | `CONVERSATION_START` | 실제 사용자 기반 기능 |
| User | `user-1`, `disabled`, `missing`, `unavailable` | User 상태별 Mock 응답 구분 |

## Procedure — When

1. 미초기화 또는 Global OFF 상태에서 사용자 기반 평가를 호출한다.
2. Global ON, Feature OFF 상태에서 사용자 기반 평가를 호출한다.
3. Global/Feature ON 상태에서 User 상태별 평가를 호출한다.

## Expected Results — Then

1. 미초기화 또는 Global 거절 사유를 그대로 반환하고 User Provider를 호출하지 않는다.
2. Feature OFF는 `FEATURE_AI_DISABLED`를 반환하고 User Provider를 호출하지 않는다.
3. Global과 Feature가 모두 허용된 경우에만 User Provider를 호출한다.
4. `ENABLED`는 `ALLOWED`, `DISABLED`는 `USER_AI_DISABLED`다.
5. `NOT_CONFIGURED`는 `USER_POLICY_NOT_CONFIGURED`, `Unavailable`은 `USER_POLICY_UNAVAILABLE`이다.

## Automated Test Mapping

| 테스트 클래스 | 테스트 메서드 | 검증하는 기대 결과 |
|---|---|---|
| [AiExecutionPolicyServiceTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyServiceTests.java) | `rejectsWhenSnapshotHasNotBeenInitialized`, `rejectsWhenGlobalPolicyIsDisabled` | Then 1 |
| [AiExecutionPolicyServiceTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyServiceTests.java) | `rejectsFeatureBeforeResolvingUserPolicy` | Then 2 |
| [AiExecutionPolicyServiceTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyServiceTests.java) | `allowsEnabledOverallUserPolicyAfterGlobalAndFeature` | Then 3, 4 |
| [AiExecutionPolicyServiceTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyServiceTests.java) | `mapsOverallUserPolicyRejections` | Then 4, 5 |

## Execution Command

```sh
./gradlew test \
  --tests 'com.ucware.ai_orchestrator.policy.application.AiExecutionPolicyServiceTests.rejectsWhenSnapshotHasNotBeenInitialized' \
  --tests 'com.ucware.ai_orchestrator.policy.application.AiExecutionPolicyServiceTests.rejectsWhenGlobalPolicyIsDisabled' \
  --tests 'com.ucware.ai_orchestrator.policy.application.AiExecutionPolicyServiceTests.rejectsFeatureBeforeResolvingUserPolicy' \
  --tests 'com.ucware.ai_orchestrator.policy.application.AiExecutionPolicyServiceTests.allowsEnabledOverallUserPolicyAfterGlobalAndFeature' \
  --tests 'com.ucware.ai_orchestrator.policy.application.AiExecutionPolicyServiceTests.mapsOverallUserPolicyRejections' \
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
| Passed / Failed / Skipped | 5 / 0 / 0 |

## Cleanup / Postconditions

- 외부 시스템이나 영속 데이터를 사용하지 않는다.
- Snapshot과 Mock은 각 테스트 종료 후 폐기된다.

## Limitations

- 현재 User Policy는 전체 AI 사용 여부이며 사용자별 Feature Policy는 평가하지 않는다.
- Dirty working tree 실행이므로 상위 Verification 완료 판정 evidence로 사용하지 않는다.
