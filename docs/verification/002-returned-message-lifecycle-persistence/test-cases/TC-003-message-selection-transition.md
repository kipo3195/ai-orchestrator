# TC-003. 메시지 선정과 확인 대기 전이

| 항목 | 값 |
|---|---|
| Verification | [Returned Message Lifecycle Persistence Verification](../README.md) |
| Test Level | Workflow / Persistence Integration |

## Verification Purpose

새 lifecycle을 생성할 권한을 얻은 처리자만 Message Query를 수행하고, 조회 결과를 `SKIPPED_NO_MESSAGES`, `AWAITING_CONFIRMATION` 또는 명시적인 선정 실패 상태로 정확히 반영하는지 검증한다.

## Requirement Traceability

| 구분 | 참조 | 관련 요구사항 |
|---|---|---|
| Decision | [Persistence threshold](../../../decisions/003-returned-message-lifecycle-persistence.md#persistence-threshold) | Message Query 전에 lifecycle을 생성하고 새로 생성할 권한을 얻은 처리자만 최초 작업을 진행한다. |
| Engineering | [Message Selection and Confirmation](../../../engineering/003-returned-message-lifecycle-processing.md#message-selection-and-confirmation) | unread 결과, confirmation correlation, DB 시각 만료와 선정 실패 전이를 정의한다. |
| Use Case | [Message Selection and Read/Delete Semantics](../../../../../omni-ai/omni-ai-architecture/docs/use-cases/02-returned-message-topics.md#message-selection-and-readdelete-semantics) | 복귀 시점에 선정한 unread 집합을 확인 이후에도 유지한다. |
| Implementation | Planned | 구현 후 Message Query Port, lifecycle use case와 repository transition에 매핑한다. |

## Risk Addressed

- 중복 Trigger 처리자가 Message Query와 확인 팝업을 중복 시작할 수 있다.
- unread가 없는데 확인 또는 AI 실행이 시작될 수 있다.
- selection과 confirmation correlation이 원자적으로 보존되지 않아 이후 응답을 검증할 수 없을 수 있다.
- 메시지 조회 실패가 정상적인 no-message 결과로 기록될 수 있다.

## Test Level and Boundary

- 테스트 수준: Application Workflow 및 Persistence Integration
- 검증 대상: lifecycle 생성 결과 이후 Message Query 호출과 `DETECTED` 조건부 전이
- 실제 사용 컴포넌트: application flow, domain 상태 규칙과 repository adapter
- Test Double: unread count, selection과 실패를 제어하는 Message Query Port Stub, confirmation sender Spy
- 검증 제외: Message owner 내부 unread SQL, Queue retry와 실제 Realtime 전송

### Rationale

workflow 테스트로 Port 호출 순서와 부수 효과를 검증하고, 상태와 selection/correlation 저장의 원자성은 repository integration test로 보완한다.

## Preconditions — Given

1. 생성 직후 `DETECTED` lifecycle 또는 이미 존재하는 lifecycle이 있다.
2. Message Query는 unread 없음, 유효한 selection 또는 복구 불가능한 실패를 반환할 수 있다.
3. confirmation timeout은 양수이며 만료 시각 생성에 DB 시각을 사용한다.

## Test Data

| 항목 | 값 | 선택 이유 |
|---|---|---|
| unread 없음 | `unreadCount=0` | 확인과 AI 실행을 생략하는 terminal 분기 |
| unread 있음 | `unreadCount=3`, `selection-1` | confirmation 준비 정상 분기 |
| Query correlation 오류 | 다른 `absencePeriodId` 또는 누락된 selection | 외부 계약 불일치 경계 |
| Query 실패 | timeout 후 복구 불가능 판정 | `FAILED/DETECTION` 분기 |

## Procedure — When

1. 새 lifecycle 생성 결과로 unread 없음 Query 결과를 처리한다.
2. 새 lifecycle 생성 결과로 unread와 selection이 있는 결과를 처리한다.
3. correlation이 불일치하거나 필수 selection이 없는 결과를 처리한다.
4. 복구 불가능한 Message Query 실패를 처리한다.
5. 기존 lifecycle 또는 상태 전이 경쟁에서 패한 처리자로 같은 흐름을 시도한다.

## Expected Results — Then

1. 새 lifecycle 생성 권한을 얻은 처리자만 Message Query를 한 번 수행한다.
2. unread가 없으면 `SKIPPED_NO_MESSAGES`로 전이하고 confirmation과 AI 실행을 호출하지 않는다.
3. unread가 있으면 selection, unread count, confirmation correlation, delivery `PENDING`과 DB 시각 기반 만료를 저장하며 `AWAITING_CONFIRMATION`으로 전이한다.
4. `AWAITING_CONFIRMATION` 전이 전에는 confirmation을 전송하지 않고, 전이 또는 claim에 성공한 처리자만 전송을 시도한다.
5. 복구 불가능한 조회 실패는 `FAILED`, `failureStage=DETECTION`으로 기록하며 내부 예외를 외부 상태에 노출하지 않는다.
6. correlation이 불일치하는 Query 결과는 정상 selection으로 수용하지 않는다.
7. 이미 존재하는 lifecycle과 conditional update 패자는 Message Query 또는 confirmation을 새로 시작하지 않는다.

## Review

| 리뷰 | 상태 | Reviewed At | Reviewed Commit | Notes |
|---|---|---|---|---|
| Scenario | Ready for Review | - | - | 신규 시나리오 리뷰 대기 |
| Assertion | Draft | - | - | 자동화 구현과 매핑 완료 후 assertion 리뷰 필요 |

## Automated Test Mapping

| 테스트 클래스 | 테스트 메서드 | 검증하는 기대 결과 |
|---|---|---|
| TBD | message selection workflow branch tests | Then 1~7 |
| TBD | selection and confirmation persistence integration tests | Then 3~5 |

## Execution Command

구현 후 실제 workflow와 persistence test suite 이름으로 교체한다.

```sh
./gradlew test --tests '*returnedmessage*MessageSelection*' --rerun-tasks
```

## Execution Evidence

이 TC의 실행 결과는 상위 Verification의 최신 전체 실행 증적에서 관리한다. TC 문서에는 실행 메타데이터를 중복 기록하지 않는다.

- Latest Verification Execution: [상위 Verification 문서](../README.md#last-verification-execution)
- TC 결과 확인: 연결된 execution evidence에서 `Automated Test Mapping`에 해당하는 테스트 결과를 확인한다.

## Cleanup / Postconditions

- lifecycle fixture는 rollback 또는 명시적으로 정리한다.
- Stub과 Spy invocation은 테스트 종료 후 폐기한다.

## Limitations

- confirmation의 실제 network delivery와 downstream idempotency는 검증하지 않는다.
- Message Query의 내부 retry와 timeout 정책은 Message owner 책임이다.
