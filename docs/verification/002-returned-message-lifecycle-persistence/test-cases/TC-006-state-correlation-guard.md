# TC-006. 상태 및 execution correlation 보호

| 항목 | 값 |
|---|---|
| Verification | [Returned Message Lifecycle Persistence Verification](../README.md) |
| Test Level | Domain / Persistence Integration |

Required 여부, 자동화 상태와 `Last Verification Execution` 기준 실행 결과는 [상위 Verification 문서](../README.md)에서 관리한다. 이 문서의 `Last Execution`은 해당 TC의 최근 단독 또는 전체 실행을 기록하며, 단독 실행만으로 상위 Verification의 상태를 변경하지 않는다.

## Verification Purpose

허용된 현재 상태와 저장된 correlation이 모두 일치할 때만 lifecycle 결과가 반영되고, 다른 execution의 결과나 terminal 상태 이후 도착한 중복·지연 결과가 현재 상태를 변경하지 않는지 검증한다.

## Requirement Traceability

| 구분 | 참조 | 관련 요구사항 |
|---|---|---|
| Decision | [Atomic state transition](../../../decisions/003-returned-message-lifecycle-persistence.md#atomic-state-transition) | AI 결과 반영 시 현재 상태와 저장된 `execution_id`를 함께 비교한다. |
| Engineering | [Lifecycle State Model](../../../engineering/003-returned-message-lifecycle-processing.md#lifecycle-state-model) | 허용 전이와 terminal 상태 불변성을 정의한다. |
| Engineering | [AI Execution Correlation](../../../engineering/003-returned-message-lifecycle-processing.md#ai-execution-correlation) | stale result와 terminal 이후 결과를 반영하지 않는다. |
| Implementation | Planned | 구현 후 domain transition, repository conditional update와 result handler에 매핑한다. |

## Risk Addressed

- 이전 execution의 늦은 결과가 새 실행 또는 terminal 상태를 덮어쓸 수 있다.
- 현재 상태를 확인하지 않은 update가 허용되지 않은 역방향 전이를 만들 수 있다.
- conditional update 0건 이후에도 result delivery 같은 외부 side effect가 실행될 수 있다.

## Test Level and Boundary

- 테스트 수준: Domain Unit 및 Persistence Integration
- 검증 대상: 허용 상태 전이, terminal 불변성, 상태와 `executionId`를 포함한 conditional update
- 실제 사용 컴포넌트: domain model, repository SQL과 result application use case
- Test Double: 후속 delivery 호출 여부 관찰용 Spy
- 검증 제외: Omni AI Server start/status/cancel protocol과 result payload 품질

### Rationale

허용 전이 자체는 domain test로 빠르게 검증하고, 상태와 correlation이 SQL 조건에 모두 포함되는지는 실제 update row count로 검증한다.

## Preconditions — Given

1. 서로 다른 `executionId`를 가진 `PROCESSING` lifecycle fixture가 있다.
2. 각 terminal 상태의 lifecycle fixture가 있다.
3. result handler의 후속 delivery 또는 상태 반영 호출을 관찰할 수 있다.

## Test Data

| 항목 | 값 | 선택 이유 |
|---|---|---|
| 저장 execution | `execution-1` | 정상 correlation 기준 |
| stale execution | `execution-0` 또는 `execution-2` | 불일치 결과 거절 |
| 현재 상태 | `PROCESSING`, `DELIVERY_PENDING`, 각 terminal 상태 | 허용 전이와 상태 조건 확인 |
| 중복 결과 | 동일 correlation의 반복 callback/event | update 0건 멱등 처리 확인 |

## Procedure — When

1. 저장된 `executionId`와 일치하는 결과를 현재 허용 상태에 반영한다.
2. 다른 `executionId` 결과와 동일 결과의 중복을 반영한다.
3. terminal 상태에 성공, 실패 또는 취소 결과를 반영한다.
4. 잘못된 현재 상태에서 다음 단계 전이를 시도한다.
5. 각 conditional update 결과와 후속 외부 호출을 확인한다.

## Expected Results — Then

1. 상태와 `executionId`가 모두 일치하는 update만 row count `1`을 반환하고 허용된 다음 상태로 전이한다.
2. 다른 `executionId`, 잘못된 현재 상태와 중복 결과는 row count `0`이며 lifecycle을 변경하지 않는다.
3. terminal 상태는 다른 terminal 또는 non-terminal 상태로 전이하지 않는다.
4. conditional update `0`건인 처리자는 현재 lifecycle을 조회해 멱등 결과를 반환하고 후속 delivery를 시작하지 않는다.
5. tenant가 다른 같은 request/execution 식별자는 조회하거나 갱신하지 않는다.

## Automated Test Mapping

| 테스트 클래스 | 테스트 메서드 | 검증하는 기대 결과 |
|---|---|---|
| TBD | domain allowed/forbidden transition tests | Then 1~3 |
| TBD | execution correlation conditional update tests | Then 1, 2, 5 |
| TBD | lost transition side-effect suppression test | Then 4 |

## Execution Command

구현 후 실제 domain과 persistence test suite 이름으로 교체한다.

```sh
./gradlew test --tests '*returnedmessage*Transition*' --tests '*returnedmessage*Correlation*' --rerun-tasks
```

## Last Execution

| 항목 | 값 |
|---|---|
| Executed At | - |
| Commit | - |
| Working Tree | - |
| Environment | - |
| Command | - |
| Execution Scope | - |
| Parent Execution / Evidence | - |
| Result | Not Run |
| Passed / Failed / Skipped | - |

## Cleanup / Postconditions

- lifecycle fixture는 rollback 또는 삭제한다.
- Spy invocation은 테스트 종료 후 폐기한다.

## Limitations

- Decision 004가 정의하는 dispatch timeout, cancellation과 reconciliation 자체는 검증하지 않는다.
- result event의 실제 transport duplicate/out-of-order delivery는 후속 contract test가 필요하다.
