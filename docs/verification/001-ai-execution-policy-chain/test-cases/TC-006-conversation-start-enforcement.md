# TC-006. Conversation Start 실행 차단

| 항목 | 값 |
|---|---|
| Verification | [AI Execution Policy Chain Verification](../README.md) |
| Test Level | Workflow |

## Verification Purpose

Conversation Start가 예약 실행 직전에 Global → `CONVERSATION_START` Feature → 전체 User Policy를 평가하고, 하나라도 거절되면 실행을 `REJECTED`로 전환한 뒤 AI port를 호출하지 않는지 검증한다.

## Requirement Traceability

| 구분 | 참조 | 관련 요구사항 |
|---|---|---|
| Engineering | [User Policy Cache-Aside Implementation — Conversation Start Integration](../../../engineering/002-user-policy-cache-aside-implementation.md#conversation-start-integration) | 실행 직전 정책을 조회하고 execution을 재확인한 뒤에만 AI를 호출한다. |
| Implementation | [ConversationStartService](../../../../src/main/java/com/ucware/ai_orchestrator/conversationstart/application/ConversationStartService.java) | 통합 정책 결정, 상태 전이와 AI port 호출 경계를 담당한다. |
| Implementation | [AiExecutionPolicyService](../../../../src/main/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyService.java) | Global/Feature/User 결정을 하나의 결과로 반환한다. |

## Risk Addressed

- 예약 이후 정책이 OFF로 변경됐는데 이전 상태로 AI가 호출될 수 있다.
- Feature 또는 User 정책이 실제 workflow 적용에서 누락될 수 있다.
- 정책 평가 중 execution이 취소·교체됐는데 AI가 실행될 수 있다.
- 모든 정책이 허용된 정상 실행이 차단되거나 결과가 전달되지 않을 수 있다.

## Test Level and Boundary

- 테스트 수준: Workflow Unit
- 검증 대상: `ConversationStartService`의 예약 Runnable 실행부터 정책 평가, 상태 전이와 AI port 호출 경계
- 실제 사용 컴포넌트: `ConversationStartService`, in-memory repository, delay policy, routing factory와 policy service
- Test Double: 예약 Runnable 캡처용 scheduler Mock, AI 호출 관찰용 port Mock, result router Mock, User Provider Mock
- 검증 제외: 실제 시간 기반 scheduler, HTTP AI adapter, Redis/DB와 result delivery endpoint

### Rationale

실제 application flow를 사용하면서 Runnable을 직접 실행하면 대기 시간 없이 실행 직전 재평가와 AI 호출 차단을 결정적으로 검증할 수 있다.

## Preconditions — Given

1. Conversation Start 실행이 예약되어 scheduler에 Runnable이 전달됐다.
2. 초기 Global/Feature와 User Policy는 허용 상태다.
3. 실행 직전 Global 또는 Feature가 OFF이거나 User resolution이 `DISABLED`인 경우가 있다.

## Test Data

| 항목 | 값 | 선택 이유 |
|---|---|---|
| Tenant | `default` | Test routing factory가 생성하는 실제 tenant |
| Feature | `CONVERSATION_START` | 검증 대상 workflow의 정책 key |
| User | `user-1` | User Policy 적용 대상 |
| 실행 식별자 | `room-session-1`, `client-session-1`, `room-1` | 예약과 current execution 상관관계 확인 |
| 변경 시각 | `2026-01-01T00:00:05Z` | 예약 이후 snapshot 변경 표현 |

## Procedure — When

1. Conversation Start를 예약하고 Runnable을 캡처한다.
2. Global OFF, Feature OFF 또는 User OFF 조건을 각각 설정한다.
3. 캡처한 Runnable을 직접 실행한다.
4. 모든 정책이 ON인 정상 실행과 AI 처리 중 취소되는 실행도 수행한다.

## Expected Results — Then

1. Global OFF이면 상태가 `REJECTED`이고 AI port는 호출되지 않는다.
2. Feature OFF이면 상태가 `REJECTED`이고 AI port는 호출되지 않는다.
3. User OFF이면 상태가 `REJECTED`이고 AI port는 호출되지 않는다.
4. 모든 정책이 ON이면 AI port를 호출하고 결과를 정상적으로 라우팅한다.
5. AI 처리 중 execution이 취소되면 결과가 취소 상태를 덮어쓰지 않는다.

## Review

| 리뷰 | 상태 | Reviewed At | Reviewed Commit | Notes |
|---|---|---|---|---|
| Scenario | Ready for Review | - | - | 신규 시나리오 리뷰 대기 |
| Assertion | Ready for Review | - | - | 신규 assertion 리뷰 대기 |

## Automated Test Mapping

| 테스트 클래스 | 테스트 메서드 | 검증하는 기대 결과 |
|---|---|---|
| [ConversationStartServiceTests](../../../../src/test/java/com/ucware/ai_orchestrator/conversationstart/application/ConversationStartServiceTests.java) | `scheduledExecutionIsRejectedWhenTenantGlobalPolicyTurnsOffBeforeExecution` | Then 1 |
| [ConversationStartServiceTests](../../../../src/test/java/com/ucware/ai_orchestrator/conversationstart/application/ConversationStartServiceTests.java) | `scheduledExecutionIsRejectedWhenFeaturePolicyTurnsOffBeforeExecution` | Then 2 |
| [ConversationStartServiceTests](../../../../src/test/java/com/ucware/ai_orchestrator/conversationstart/application/ConversationStartServiceTests.java) | `scheduledExecutionIsRejectedWhenOverallUserPolicyIsDisabled` | Then 3 |
| [ConversationStartServiceTests](../../../../src/test/java/com/ucware/ai_orchestrator/conversationstart/application/ConversationStartServiceTests.java) | `completedResultIsRoutedUsingExecutionRoutingContext` | Then 4 |
| [ConversationStartServiceTests](../../../../src/test/java/com/ucware/ai_orchestrator/conversationstart/application/ConversationStartServiceTests.java) | `resultDoesNotCompleteExecutionAfterRoomSessionIsCancelled` | Then 5 |

## Execution Command

```sh
./gradlew test \
  --tests 'com.ucware.ai_orchestrator.conversationstart.application.ConversationStartServiceTests.scheduledExecutionIsRejectedWhenTenantGlobalPolicyTurnsOffBeforeExecution' \
  --tests 'com.ucware.ai_orchestrator.conversationstart.application.ConversationStartServiceTests.scheduledExecutionIsRejectedWhenFeaturePolicyTurnsOffBeforeExecution' \
  --tests 'com.ucware.ai_orchestrator.conversationstart.application.ConversationStartServiceTests.scheduledExecutionIsRejectedWhenOverallUserPolicyIsDisabled' \
  --tests 'com.ucware.ai_orchestrator.conversationstart.application.ConversationStartServiceTests.completedResultIsRoutedUsingExecutionRoutingContext' \
  --tests 'com.ucware.ai_orchestrator.conversationstart.application.ConversationStartServiceTests.resultDoesNotCompleteExecutionAfterRoomSessionIsCancelled' \
  --rerun-tasks
```

## Execution Evidence

이 TC의 실행 결과는 상위 Verification의 최신 전체 실행 증적에서 관리한다. TC 문서에는 실행 메타데이터를 중복 기록하지 않는다.

- Latest Verification Execution: [상위 Verification 문서](../README.md#last-verification-execution)
- TC 결과 확인: 연결된 execution evidence에서 `Automated Test Mapping`에 해당하는 테스트 결과를 확인한다.

## Cleanup / Postconditions

- In-memory execution과 Mock invocation은 테스트 종료 후 폐기된다.
- 대기 중 thread가 있는 테스트는 latch를 해제하고 제한 시간 안에 종료한다.

## Limitations

- Scheduler는 Mock이며 실제 예약 시간과 thread scheduling을 검증하지 않는다.
- AI port와 result delivery는 실제 HTTP 통신이 아니다.
