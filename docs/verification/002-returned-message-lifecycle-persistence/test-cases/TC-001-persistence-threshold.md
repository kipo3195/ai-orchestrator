# TC-001. Lifecycle 저장 임계점

| 항목 | 값 |
|---|---|
| Verification | [Returned Message Lifecycle Persistence Verification](../README.md) |
| Test Level | Unit / Workflow |

Required 여부, 시나리오 리뷰 상태, 자동화 상태, Assertion 리뷰 상태와 `Last Verification Execution` 기준 실행 결과는 [상위 Verification 문서](../README.md)에서 관리한다. 이 문서의 `Scenario Review`와 `Assertion Review`는 각 최신 리뷰 증적을, `Last Execution`은 해당 TC의 최근 단독 또는 전체 실행을 기록한다. 단독 실행만으로 상위 Verification의 상태를 변경하지 않는다.

## Verification Purpose

AI 실행 후보가 될 수 있는 `USER_RETURNED` 요청만 lifecycle 생성을 시도하고, 잘못된 계약과 정책상 비대상 요청은 DB write와 외부 작업 없이 종료되는지 검증한다. Legacy `connectionType`의 적용 대상 판정은 Messenger Client Ingress의 책임이며, AI Orchestrator는 이를 opaque routing constraint로 취급한다.

## Requirement Traceability

| 구분 | 참조 | 관련 요구사항 |
|---|---|---|
| Decision | [Persistence threshold](../../../decisions/003-returned-message-lifecycle-persistence.md#persistence-threshold) | Source-qualified request body 계약, 부재 구간, Trigger 유효기간과 실행 Policy를 확인한 후보만 저장한다. |
| Engineering | [Eligibility and Persistence Threshold](../../../engineering/003-returned-message-lifecycle-processing.md#eligibility-and-persistence-threshold) | 저장 전 평가 순서, fail-closed와 Policy/Permission 확인 경계를 정의한다. |
| Implementation | `ReturnedMessageTriggerService` | Source contract 형식, eligibility policy와 repository port 호출 순서에 매핑한다. |

## Risk Addressed

- 모든 Presence 변경이 저장되어 불필요한 상태 이력과 write 부하가 발생할 수 있다.
- 만료되거나 body correlation이 일치하지 않는 요청이 Message Query 또는 AI 실행으로 이어질 수 있다.
- 부재시간 경계 또는 application instance clock 차이로 대상 여부가 달라질 수 있다.

## Test Level and Boundary

- 테스트 수준: Domain/Application Unit 및 Workflow
- 검증 대상: 저장 전 계약, routing constraint 형식, 부재시간, Trigger expiration, Policy와 Permission 평가 흐름
- 실제 사용 컴포넌트: `ReturnedMessageTriggerService`의 source contract와 eligibility 규칙 및 application orchestration
- Test Double: lifecycle repository와 외부 Port를 호출 여부 관찰용으로 사용하고 Policy/Permission 결과를 제어한다.
- 검증 제외: 실제 DB constraint, 후속 ADR에서 정의할 서비스 간 인증 구현, REST request/response mapping

### Rationale

저장 전 판단은 입력, 정책 결과, repository 호출 여부와 외부 Port 미호출로 결정적으로 관찰할 수 있다. DB에 도달한 이후의 유일성은 TC-002에서 별도로 검증한다.

## Preconditions — Given

1. Messenger Client Ingress가 적용 대상 connection과 복귀 전이를 확정하고, body의 tenant/user와 routing owner가 일치하는 source-qualified Event를 구성한다.
2. lifecycle repository와 Message Query Port의 호출 횟수를 관찰할 수 있다.
3. Policy와 Permission을 허용, 거절 또는 unavailable로 제어할 수 있다.
4. Trigger 만료 판단은 DB 시각 경계를 사용한다.

## Test Data

| 항목 | 값 | 선택 이유 |
|---|---|---|
| Connection | 양수 `connectionType` (`1`, `2`, 그 밖의 대표 양수) | 값의 의미를 재판정하지 않고 routing constraint로 전달하는지 확인 |
| 잘못된 Connection 계약 | 누락, `0`, 음수 `connectionType` | 필수 routing 값의 형식 경계 |
| 부재시간 | `minAwayDuration - 1ms`, 정확히 `minAwayDuration` | 거절과 허용 경계 |
| 시간 관계 | `offlineSince < returnedAt <= triggerExpiresAt` 및 위반 값 | 정상 계약과 잘못된 계약 구분 |
| Policy/Permission | Allowed, Denied, Unavailable | 정상 처리와 fail-closed 분기 |

## Procedure — When

1. 양수 connection type, 유효한 부재와 Trigger, 허용된 Policy/Permission으로 요청을 처리한다.
2. 누락되거나 양수가 아닌 connection type, 잘못된 시간 관계, 짧은 부재와 만료된 Trigger를 각각 처리한다.
3. Policy 또는 Permission이 거절되거나 확인 불가능한 경우를 각각 처리한다.
4. 각 경우의 lifecycle repository와 외부 Port 호출을 확인한다.

## Expected Results — Then

1. 모든 저장 전 조건이 허용된 요청만 lifecycle insert를 한 번 시도한다.
2. 정확히 `minAwayDuration`인 부재는 허용되고 그보다 짧은 부재는 거절된다.
3. 잘못된 계약, 누락되거나 양수가 아닌 connection type과 만료된 Trigger는 lifecycle을 생성하지 않는다.
4. Legacy 의미상 `1`, `2`가 아닌 양수 connection type도 Orchestrator가 기능 대상 여부를 재판정해 거절하지 않는다.
5. Policy/Permission 거절 또는 unavailable은 fail-closed이며 lifecycle을 생성하지 않는다.
6. lifecycle 생성 권한을 얻기 전에는 Message Query, confirmation delivery와 AI 실행을 호출하지 않는다.
7. application instance의 시각 차이가 Trigger expiration 결과를 바꾸지 않는다.

## Scenario Review

| 항목 | 값 |
|---|---|
| Status | Ready for Review |
| Reviewed At | - |
| Reviewer | - |
| Reviewed Commit | - |
| Review Evidence | - |
| Review Notes | 신규 시나리오 리뷰 대기 |

### Scenario Review Checklist

| 검토 항목 | 판정 | 리뷰 메모 |
|---|---|---|
| 목적과 요구사항 추적성 | Pending | - |
| 위험과 테스트 경계 | Pending | - |
| Given 조건 | Pending | - |
| When 절차 | Pending | - |
| Then 기대 결과 | Pending | - |
| 테스트 데이터와 한계 | Pending | - |

## Automated Test Mapping

| 테스트 클래스 | 테스트 메서드 | 검증하는 기대 결과 |
|---|---|---|
| `ReturnedMessageTriggerServiceTests` | `positiveConnectionTypeIsAcceptedWithoutInterpretingLegacyMeaning` | Then 1, 4 |
| `ReturnedMessageTriggerServiceTests` | `missingOrNonPositiveConnectionTypeIsRejectedAsSourceContractViolation` | Then 3, 6 |
| `ReturnedMessageTriggerServiceTests` | `exactMinimumAwayDurationIsEligibleButDuplicateDoesNotQueryAgain` | Then 1, 2 |
| `ReturnedMessageTriggerServiceTests` | `unavailablePolicyIsReturnedAsRetryableFailureBeforePersistence` | Then 5, 6 |
| TBD | DB 시각 기반 Trigger expiration test | Then 7 |

## Assertion Review

| 항목 | 값 |
|---|---|
| Status | Draft |
| Reviewed At | - |
| Reviewer | - |
| Reviewed Commit | - |
| Review Evidence | - |
| Review Notes | 자동화 구현과 매핑 완료 후 assertion 리뷰 필요 |

### Assertion Review Checklist

| 검토 항목 | 판정 | 리뷰 메모 |
|---|---|---|
| Expected Results와 assertion 매핑 | Pending | - |
| 반환값, 상태와 사유의 정확성 | Pending | - |
| 상태 전이와 persistence 부수 효과 | Pending | - |
| 외부 호출과 부수 효과 부재 | Pending | - |
| 오류, 경계값과 경쟁 조건 | Pending | - |
| 회귀 실패 감지 가능성 | Pending | - |

## Execution Command

```sh
./gradlew test --tests 'com.ucware.ai_orchestrator.returnedmessage.application.ReturnedMessageTriggerServiceTests'
```

## Last Execution

| 항목 | 값 |
|---|---|
| Executed At | 2026-10-20 10:48 KST |
| Commit | `d8e9321` |
| Working Tree | Modified |
| Environment | Local Gradle / Java 17 |
| Command | `./gradlew test --tests 'com.ucware.ai_orchestrator.returnedmessage.*'` |
| Execution Scope | Returned Message 관련 테스트 전체 |
| Parent Execution / Evidence | `build/test-results/test` |
| Result | Partial — 실행된 테스트는 통과했으며 Then 7 자동화는 TBD |
| Passed / Failed / Skipped | 24 / 0 / 0 |

## Cleanup / Postconditions

- Unit/Workflow Test Double은 테스트 종료 후 폐기한다.
- DB 시각 integration fixture를 사용하면 transaction rollback 또는 명시적 fixture 정리를 수행한다.

## Limitations

- REST Problem Details와 후속 ADR에서 정의할 서비스 간 인증 context 비교는 이 TC에서 검증하지 않는다.
- `maxAbsenceDuration` 초과 시 skip 또는 `maxLookback` 제한 중 실제 선택은 운영 정책 확정 후 추가한다.
