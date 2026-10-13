# TC-003. Tenant 정책 판단 및 AI 호출 차단

| 항목 | 값 |
|---|---|
| Verification | [Global AI Policy Snapshot Unit Verification](../README.md) |
| Test Level | Unit |

Required 여부, 자동화 상태와 `Last Verification Execution` 기준 실행 결과는 [상위 Verification 문서](../README.md)에서 관리한다. 이 문서의 `Last Execution`은 이 TC의 최근 실행을 기록하며, 단독 실행만으로 상위 Verification의 상태를 변경하지 않는다.

## Verification Purpose

초기화된 Global AI Policy snapshot에서 tenant 정책이 없거나 OFF이면 AI 실행을 거절하고 ON이면 허용하며, 평가 요청의 tenant ID가 blank이면 정책 조회 대상으로 취급하지 않고 거절하는지 검증한다. 또한 예약된 `ConversationStart` 실행이 AI port를 호출하기 직전에 최신 tenant 정책을 다시 평가하며, 그 시점의 정책이 OFF이면 실행을 `REJECTED`로 전환하고 AI를 호출하지 않는지 검증한다.

## Requirement Traceability

| 구분 | 참조 | 관련 요구사항 |
|---|---|---|
| Engineering | [Policy Immutable Snapshot Refresh — Atomic Publication](../../../engineering/001-policy-immutable-snapshot-refresh.md#atomic-publication) | 하나의 AI 요청은 현재 snapshot을 사용해 자신의 `tenantId`로 정책을 판단하며, tenant row가 없으면 OFF로 판단한다. |
| Decision | [Policy Persistence Access — Tenant Partitioning](../../../decisions/001-policy-persistence-access.md#tenant-partitioning) | Tenant별 Global Policy를 평가하고, 정책이 없는 tenant에 암묵적인 ON 기본값을 적용하지 않는다. |
| Implementation | [AiExecutionPolicyService](../../../../src/main/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyService.java) | Blank tenant ID 평가 요청을 거절하고, 유효한 tenant ID에 대해 snapshot의 Global AI 활성화 상태를 허용 또는 거절 결정으로 변환한다. |
| Implementation | [ConversationStartService](../../../../src/main/java/com/ucware/ai_orchestrator/conversationstart/application/ConversationStartService.java) | 예약 실행 직전에 tenant 정책을 평가하고, 거절되면 실행 상태를 변경한 뒤 AI port 호출 전에 반환한다. |

## Risk Addressed

- 정책 row가 없거나 OFF인 tenant의 AI 실행이 잘못 허용되어 운영 정책을 위반하고 불필요한 AI 호출과 비용이 발생하는 위험을 방지한다.
- Blank tenant ID를 정책이 없는 정상 tenant와 동일하게 처리해 잘못된 평가 입력이 숨겨지는 위험을 방지한다.
- 예약 시점 이후 정책이 OFF로 변경되었는데도 이전 정책 판단을 사용해 AI를 호출하는 위험을 방지한다.
- ON인 tenant까지 잘못 거절하여 정상적인 AI 기능을 사용할 수 없게 되는 회귀를 방지한다.

## Test Level and Boundary

- 테스트 수준: Unit
- 검증 대상: `AiExecutionPolicyService`의 tenant별 정책 판단과 `ConversationStartService`의 실행 직전 정책 평가 및 AI 호출 차단
- 실제 사용 컴포넌트: `GlobalAiPolicySnapshot`, `AiExecutionPolicyService`, `ConversationStartService`, `InMemoryConversationStartRepository`, `ConversationStartDelayPolicy`와 `RoutingRefFactory`를 실제 객체로 사용한다.
- Test Double: 정책 서비스 테스트에서는 고정 snapshot을 반환하는 `GlobalAiPolicyProvider` Stub을 사용한다. Workflow 테스트에서는 snapshot을 실행 전에 교체할 수 있도록 `AtomicReference` 기반 Provider를 사용하고, `ConversationStartScheduler`, `ConversationStartAiPort`와 `AiResultRouter`는 호출 여부를 관찰하기 위한 Mock으로 대체한다.
- 검증 제외: 실제 Policy DB와 JDBC mapping, 실제 Spring scheduler와 시간 경과, 실제 HTTP AI adapter 및 다른 AI workflow의 정책 적용

### Rationale

Tenant 정책의 누락/OFF/ON 분기는 snapshot과 정책 서비스의 반환값으로 직접 관찰할 수 있다. 예약 실행의 Runnable을 직접 실행하면 실제 대기 없이 AI 호출 직전의 재평가, 실행 상태 변경과 AI port 미호출을 결정적으로 검증할 수 있으므로 단위 테스트로 핵심 위험을 확인할 수 있다. 실제 DB 변경부터 snapshot refresh를 거쳐 AI adapter까지 이어지는 연결은 별도 통합 또는 workflow 테스트 경계다.

## Preconditions — Given

1. 정책 판단 테스트에는 정상 로딩이 완료된 `initialized=true` snapshot이 있다.
2. Snapshot에는 ON인 `tenant-1`, OFF인 `tenant-2`가 있으며, `tenant-missing`의 정책은 존재하지 않는다. Blank tenant ID는 유효한 평가 요청이 아니다.
3. Workflow 테스트에서 `ConversationStart`를 예약할 때 대표 tenant인 `default`의 정책은 ON이다.
4. 예약된 작업을 실행하기 전에 `default` tenant의 정책을 OFF로 변경한 새 snapshot을 Provider에 설정한다.
5. Scheduler는 예약된 Runnable을 캡처하고, AI port는 호출 여부를 검증할 수 있는 Mock이다.

## Test Data

| 항목 | 값 | 선택 이유 |
|---|---|---|
| ON tenant | `tenant-1=true` | 명시적으로 활성화된 tenant만 허용되는지 확인한다. |
| OFF tenant | `tenant-2=false` | 명시적으로 비활성화된 tenant가 거절되는지 확인한다. |
| 정책 누락 tenant | `tenant-missing` | 암묵적인 ON 기본값 없이 OFF로 판단되는지 확인한다. |
| Blank tenant ID | `"  "` | 공백으로만 구성된 평가 요청이 정책 누락으로 처리되지 않고 입력 검증에서 거절되는지 확인한다. |
| 대표 workflow tenant | `default` | `ConversationStartServiceTests`의 routing 설정이 생성하는 tenant ID다. |
| 예약 시 정책 | `default=true`, `loadedAt=2026-01-01T00:00:00Z` | 실행 예약 시점에는 정책이 허용 상태임을 표현한다. |
| 실행 직전 정책 | `default=false`, `loadedAt=2026-01-01T00:00:05Z` | 예약 후 정책 변경이 실제 AI 호출 직전 평가에 반영되는지 확인한다. |

## Procedure — When

### Scenario A — Tenant 정책 판단

1. ON, OFF 또는 정책 누락 상태를 포함하는 initialized snapshot으로 `AiExecutionPolicyService`를 생성한다.
2. 각 tenant ID에 대해 `evaluateGlobal(tenantId)`를 호출한다.
3. Blank tenant ID로 `evaluateGlobal(tenantId)`를 호출한다.

### Scenario B — 실행 직전 OFF 정책에 따른 AI 호출 차단

1. `default` tenant 정책이 ON인 상태에서 `ConversationStartService.start(...)`를 호출한다.
2. Scheduler에 전달된 예약 실행 Runnable을 캡처한다.
3. Runnable을 실행하기 전에 현재 snapshot을 `default=false`인 새 snapshot으로 교체한다.
4. 캡처한 Runnable을 직접 실행한다.

## Expected Results — Then

1. 정책이 없는 tenant의 판단 결과는 `allowed=false`이고 거절 사유는 `GLOBAL_AI_DISABLED`이다.
2. 명시적으로 OFF인 tenant의 판단 결과는 `allowed=false`이다.
3. 명시적으로 ON인 tenant의 판단 결과는 `allowed=true`이다.
4. Blank tenant ID 평가 요청은 `IllegalArgumentException`으로 거절된다.
5. 예약 작업은 예약 시점의 ON 상태를 고정해서 사용하지 않고 실행 직전의 OFF snapshot을 평가한다.
6. 실행 직전 정책이 OFF이면 `ConversationStart` 실행 상태는 `REJECTED`가 된다.
7. 정책에 의해 거절된 실행은 `ConversationStartAiPort.generateSuggestion(...)`을 호출하지 않는다.

## Automated Test Mapping

| 테스트 클래스 | 테스트 메서드 | 검증하는 기대 결과 |
|---|---|---|
| [AiExecutionPolicyServiceTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyServiceTests.java) | `missingTenantPolicyDefaultsToDisabled` | Then 1 |
| [AiExecutionPolicyServiceTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyServiceTests.java) | `allowsOnlyEnabledTenant` | Then 2, 3 |
| [AiExecutionPolicyServiceTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyServiceTests.java) | `rejectsBlankTenantId` | Then 4 |
| [ConversationStartServiceTests](../../../../src/test/java/com/ucware/ai_orchestrator/conversationstart/application/ConversationStartServiceTests.java) | `scheduledExecutionIsRejectedWhenTenantGlobalPolicyTurnsOffBeforeExecution` | Then 5, 6, 7 |

## Execution Command

```sh
./gradlew test \
  --tests 'com.ucware.ai_orchestrator.policy.application.AiExecutionPolicyServiceTests.missingTenantPolicyDefaultsToDisabled' \
  --tests 'com.ucware.ai_orchestrator.policy.application.AiExecutionPolicyServiceTests.allowsOnlyEnabledTenant' \
  --tests 'com.ucware.ai_orchestrator.policy.application.AiExecutionPolicyServiceTests.rejectsBlankTenantId' \
  --tests 'com.ucware.ai_orchestrator.conversationstart.application.ConversationStartServiceTests.scheduledExecutionIsRejectedWhenTenantGlobalPolicyTurnsOffBeforeExecution' \
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
| Passed / Failed / Skipped | 4 / 0 / 0 |

## Cleanup / Postconditions

- 외부 시스템이나 영속 데이터를 사용하지 않으므로 별도 정리가 필요하지 않다.
- 각 테스트가 생성한 snapshot, in-memory repository와 Mock은 테스트 종료 후 폐기된다.

## Limitations

- AI 호출 차단 workflow는 `ConversationStartService`를 대표 경로로 검증한다. 향후 추가되는 AI 실행 기능의 정책 적용 여부는 해당 기능의 테스트에서 별도로 검증해야 한다.
- 정책에 의해 거절된 실행은 `REJECTED` 상태만 저장한다. 구체적인 거절 사유(`GLOBAL_AI_DISABLED` 등)는 로그에 기록되지만 실행 상태에는 보존되지 않으므로, 실행 객체만으로는 정책 거절 원인을 사후 식별할 수 없다.
- 실제 Policy DB 변경, snapshot refresh와 AI adapter 호출을 포함하는 전체 연결은 검증하지 않는다.
- 실제 Spring scheduler의 지연 실행 대신 캡처한 Runnable을 직접 실행하므로 scheduler 등록과 실제 시간 경과는 검증하지 않는다.
- 미초기화 snapshot의 `GLOBAL_POLICY_UNAVAILABLE` 판단은 [TC-001](TC-001-uninitialized-snapshot.md)에서 검증한다.
