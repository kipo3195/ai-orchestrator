# TC-008. USER_RETURNED REST ingress 계약

| 항목 | 값 |
|---|---|
| Verification | [Returned Message Lifecycle Persistence Verification](../README.md) |
| Test Level | Controller / Contract + Application Unit |

## Verification Purpose

Messenger Client Ingress가 제출한 source-qualified `USER_RETURNED` HTTP body를 AI Orchestrator가 계약대로 검증·mapping하고, owner와 routing constraint를 `routingRef`의 단일 입력 출처에서 읽는지 검증한다.

## Requirement Traceability

| 구분 | 참조 | 관련 요구사항 |
|---|---|---|
| Use Case | [Returned Message Topics](../../../../../omni-ai/omni-ai-architecture/docs/use-cases/02-returned-message-topics.md) | USER_RETURNED REST Request와 trusted routingRef 계약 |
| Upstream | [WS Presence Trigger](../../../../../git/ucware_server6.0_maven/docs/verification/001-returned-user-presence-trigger/README.md) | 상태 전이와 Desktop 적용 대상 판단은 upstream이 소유 |
| Implementation | [ReturnedMessageController](../../../../src/main/java/com/ucware/ai_orchestrator/returnedmessage/controller/ReturnedMessageController.java) | HTTP validation, routing scope 생성과 application request mapping |
| Implementation | [ReturnedMessageTriggerService](../../../../src/main/java/com/ucware/ai_orchestrator/returnedmessage/application/ReturnedMessageTriggerService.java) | source 시간 계약과 opaque positive connectionType을 검증한다. |

## Risk Addressed

- owner와 routing constraint가 서로 다른 입력 출처에서 구성되면 다른 tenant, user 또는 Client session의 lifecycle과 결과 routing이 섞일 수 있다.
- 필수 correlation 또는 시각 필드가 누락된 요청이 application까지 전달되면 잘못된 lifecycle write나 외부 작업이 시작될 수 있다.
- Orchestrator가 legacy connectionType 의미를 재판정하면 Presence owner와 적용 대상 정책이 중복되거나 불일치할 수 있다.

## Test Level and Boundary

- 테스트 수준: Controller slice / Application unit contract
- 검증 대상: `ReturnedMessageController`의 request validation·mapping과 `ReturnedMessageTriggerService`의 source contract validation
- 실제 사용 컴포넌트: Spring MVC validation, request/domain record와 routing value object
- Test Double: Controller 테스트의 `HandleUserReturned`; application 테스트의 repository, policy evaluator와 외부 port
- 검증 제외: upstream 인증과 Presence 상태 판정, 실제 DB write, Message Query, confirmation delivery와 네트워크 통신

### Rationale

Controller slice는 HTTP status, 성공 응답 body, Problem Details, routingRef mapping과 application 호출 여부를 관찰할 수 있다. Application unit test는 legacy 숫자의 의미를 해석하지 않으면서 양수 constraint만 허용하는 방어적 계약을 외부 I/O 없이 검증한다.

## Preconditions — Given

1. `POST /api/v1/returned-messages` endpoint가 활성화되어 있다.
2. application use case를 Test Double로 대체해 전달된 request를 관찰할 수 있다.
3. application source contract 테스트에서는 repository와 모든 외부 port를 Test Double로 대체한다.

## Test Data

```json
{
  "requestId": "request-1",
  "triggerId": "trigger-1",
  "absencePeriodId": "absence-1",
  "offlineSince": "2026-10-19T05:30:00Z",
  "returnedAt": "2026-10-19T06:00:00Z",
  "occurredAt": "2026-10-19T06:00:00Z",
  "expiresAt": "2026-10-19T06:05:00Z",
  "routingRef": {
    "tenantId": "default",
    "userId": "user-1",
    "targetRef": "client-session-1",
    "connectionType": 1
  }
}
```

## Procedure — When

1. 정상 `USER_RETURNED` body를 endpoint에 제출하고 application use case에 전달된 request를 캡처한다.
2. connectionType을 누락하거나 0·음수로 설정한 body를 제출하고 HTTP 응답 및 application 미호출을 확인한다.
3. 필수 correlation과 시각 필드를 하나씩 누락한 body를 제출한다.
4. 시간 정합성이 잘못된 request를 application에 전달하고 HTTP Problem Details 변환을 확인한다.
5. application source contract에 `null`, 0, 음수와 legacy 의미를 알 수 없는 양수 connectionType을 각각 전달한다.

## Expected Results — Then

1. 정상 body는 `200 OK`로 응답하고 requestId, triggerId, outcome, reason과 lifecycle status를 반환한다.
2. tenantId, userId, targetRef와 connectionType은 `routingRef`에서 application request로 전달한다.
3. routing scope는 `ABSENCE_PERIOD + absencePeriodId`로 생성한다.
4. connectionType이 누락되거나 비양수이면 4xx로 거절하고 lifecycle/application 처리를 시작하지 않는다.
5. requestId, triggerId, absencePeriodId 및 시각 필드가 누락되면 Bean Validation 오류로 거절한다.
6. `offlineSince >= returnedAt` 등 시간 정합성 위반은 application source contract에서 side effect 전에 거절되고 HTTP 400 Problem Details로 변환된다.
7. Orchestrator는 connectionType 1, 2의 legacy 의미를 재판정하지 않고 양수 routing constraint로 처리한다.

## Review

| 리뷰 | 상태 | Reviewed At | Reviewed Commit | Notes |
|---|---|---|---|---|
| Scenario | Ready for Review | - | - | 신규 시나리오 리뷰 대기 |
| Assertion | Ready for Review | - | - | 신규 assertion 리뷰 대기 |

## Automated Test Mapping

| 테스트 클래스 | 테스트 메서드 | 검증 범위 |
|---|---|---|
| [ReturnedMessagePolicyErrorResponseTests](../../../../src/test/java/com/ucware/ai_orchestrator/returnedmessage/controller/ReturnedMessagePolicyErrorResponseTests.java) | `userReturnedOwnerIsReadFromRoutingRef` | Expected 1~3 |
| [ReturnedMessagePolicyErrorResponseTests](../../../../src/test/java/com/ucware/ai_orchestrator/returnedmessage/controller/ReturnedMessagePolicyErrorResponseTests.java) | `userReturnedRequiresConnectionType` | Expected 4와 application 미호출 |
| [ReturnedMessagePolicyErrorResponseTests](../../../../src/test/java/com/ucware/ai_orchestrator/returnedmessage/controller/ReturnedMessagePolicyErrorResponseTests.java) | `userReturnedRejectsNonPositiveConnectionType` | Expected 4의 HTTP validation과 application 미호출 |
| [ReturnedMessagePolicyErrorResponseTests](../../../../src/test/java/com/ucware/ai_orchestrator/returnedmessage/controller/ReturnedMessagePolicyErrorResponseTests.java) | `userReturnedRejectsMissingRequiredField` | Expected 5 |
| [ReturnedMessagePolicyErrorResponseTests](../../../../src/test/java/com/ucware/ai_orchestrator/returnedmessage/controller/ReturnedMessagePolicyErrorResponseTests.java) | `sourceTimeContractViolationUsesBadRequestProblemDetails` | Expected 6의 HTTP Problem Details 변환 |
| [ReturnedMessageTriggerServiceTests](../../../../src/test/java/com/ucware/ai_orchestrator/returnedmessage/application/ReturnedMessageTriggerServiceTests.java) | `missingOrNonPositiveConnectionTypeIsRejectedAsSourceContractViolation` | Expected 4의 application 방어 검증 |
| [ReturnedMessageTriggerServiceTests](../../../../src/test/java/com/ucware/ai_orchestrator/returnedmessage/application/ReturnedMessageTriggerServiceTests.java) | `invalidSourceTimeContractIsRejectedBeforeSideEffects` | Expected 6과 외부 부수 효과 부재 |
| [ReturnedMessageTriggerServiceTests](../../../../src/test/java/com/ucware/ai_orchestrator/returnedmessage/application/ReturnedMessageTriggerServiceTests.java) | `positiveConnectionTypeIsAcceptedWithoutInterpretingLegacyMeaning` | Expected 7 |

## Execution Command

```sh
./gradlew test --tests 'com.ucware.ai_orchestrator.returnedmessage.controller.ReturnedMessagePolicyErrorResponseTests' --tests 'com.ucware.ai_orchestrator.returnedmessage.application.ReturnedMessageTriggerServiceTests' --rerun-tasks
```

## Execution Evidence

이 TC의 실행 결과는 상위 Verification의 최신 전체 실행 증적에서 관리한다. TC 문서에는 실행 메타데이터를 중복 기록하지 않는다.

- Latest Verification Execution: [상위 Verification 문서](../README.md#last-verification-execution)
- TC 결과 확인: 연결된 execution evidence에서 `Automated Test Mapping`에 해당하는 테스트 결과를 확인한다.

## Cleanup / Postconditions

- Spring test context, captured request와 Mock invocation은 테스트 종료 후 폐기된다.
- 외부 DB, Redis 또는 네트워크 리소스를 생성하지 않는다.

## Limitations

- upstream의 Access Token/SID 검증과 Desktop connectionType 적용 대상 판단은 WS 검증 범위다.
- 서비스 간 인증 context 복원은 향후 인증 계약 완성 후 추가로 검증한다.
- Controller 테스트는 application use case를 Mock으로 사용하므로 실제 lifecycle persistence와 외부 port 호출은 이 TC가 보장하지 않는다.
