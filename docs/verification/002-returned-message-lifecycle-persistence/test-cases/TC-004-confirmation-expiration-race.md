# TC-004. 사용자 확인과 만료 경쟁

| 항목 | 값 |
|---|---|
| Verification | [Returned Message Lifecycle Persistence Verification](../README.md) |
| Test Level | Workflow / Persistence Integration / Concurrency |

## Verification Purpose

유효한 YES, NO와 expiration이 동시에 경쟁해도 상태·correlation·DB 만료 조건을 만족한 하나의 전이만 성공하고, 전이 승자만 후속 외부 작업을 시작하는지 검증한다.

## Requirement Traceability

| 구분 | 참조 | 관련 요구사항 |
|---|---|---|
| Decision | [Atomic state transition](../../../decisions/003-returned-message-lifecycle-persistence.md#atomic-state-transition) | 현재 상태, 만료와 correlation을 조건으로 갱신하고 0건이면 외부 작업을 시작하지 않는다. |
| Decision | [Confirmation expiration](../../../decisions/003-returned-message-lifecycle-persistence.md#confirmation-expiration) | YES/NO 유효성을 DB 절대 시각으로 판단하고 sweeper 실행 여부와 분리한다. |
| Engineering | [Confirmation Handling](../../../engineering/003-returned-message-lifecycle-processing.md#confirmation-handling) | NO, YES, Policy/Permission 재평가와 AI start 순서를 정의한다. |
| Implementation | Planned | 구현 후 confirmation use case, repository conditional update와 AI execution gateway에 매핑한다. |

## Risk Addressed

- 중복 YES가 여러 AI execution을 생성할 수 있다.
- YES, NO와 timeout의 경쟁에서 서로 모순되는 상태와 side effect가 발생할 수 있다.
- sweeper가 늦게 실행됐다는 이유로 만료된 응답이 수용될 수 있다.
- application instance clock 차이로 같은 응답의 유효성 결과가 달라질 수 있다.
- client가 보낸 selection 또는 routing 값을 신뢰해 다른 correlation으로 실행할 수 있다.

## Test Level and Boundary

- 테스트 수준: Application Workflow, Persistence Integration 및 Concurrency
- 검증 대상: confirmation 인증/correlation 검증, Policy/Permission 재평가와 conditional transition
- 실제 사용 컴포넌트: lifecycle use case, domain 상태 규칙, repository SQL과 DB current time
- Test Double: Policy/Permission 결과와 AI execution gateway 호출 관찰
- 검증 제외: Omni AI Server의 실제 start 멱등성, HTTP confirmation endpoint와 Realtime transport

### Rationale

workflow 테스트는 평가 순서와 외부 호출 부재를 검증하고, 실제 DB conditional update와 동시 경쟁은 integration test로 검증해야 한다.

## Preconditions — Given

1. 유효한 confirmation correlation과 만료 시각을 가진 `AWAITING_CONFIRMATION` lifecycle이 있다.
2. 두 개 이상의 처리자가 같은 DB row를 독립적으로 갱신할 수 있다.
3. Policy/Permission을 허용, 거절 또는 unavailable로 제어할 수 있다.
4. AI execution gateway 호출 횟수와 `executionId`를 관찰할 수 있다.

## Test Data

| 항목 | 값 | 선택 이유 |
|---|---|---|
| 유효한 응답 | DB 현재 시각보다 미래인 만료 시각 | YES와 NO 정상 분기 |
| 만료 경계 | 만료 시각이 DB 현재 시각과 같거나 과거 | `>` 유효 조건과 `<=` 만료 조건 경계 |
| 경쟁 조합 | YES/YES, YES/NO, YES/expiration, NO/expiration | 단일 전이 보장 핵심 조합 |
| 잘못된 correlation | 다른 request, user, period 또는 confirmation correlation | 정보 격리와 변조 방지 |
| 재평가 | Allowed, Denied, Unavailable | `PROCESSING` 또는 `BLOCKED_BY_POLICY` 분기 |

## Procedure — When

1. 유효한 NO와 YES를 각각 단독 처리한다.
2. 같은 YES 또는 NO를 순차 중복 처리한다.
3. 각 경쟁 조합의 시작점을 barrier로 맞추고 동시에 처리한다.
4. 만료됐지만 아직 `AWAITING_CONFIRMATION`인 row에 YES와 NO를 보낸다.
5. YES 재평가 중 NO 또는 expiration이 먼저 전이하도록 순서를 제어한다.
6. 잘못된 correlation 또는 다른 tenant/user의 확인을 처리한다.

## Expected Results — Then

1. 유효한 NO는 한 번만 `DECLINED`로 전이하고 AI execution을 생성하지 않는다.
2. 유효하고 재평가가 허용된 YES는 stable `executionId`와 함께 한 번만 `PROCESSING`으로 전이한다.
3. Policy/Permission 거절 또는 unavailable은 fail-closed로 `BLOCKED_BY_POLICY`가 되고 AI를 호출하지 않는다.
4. 각 경쟁 조합에서 conditional update 한 건만 성공하고 lifecycle은 하나의 허용 상태로 수렴한다.
5. `PROCESSING` 전이에 성공한 처리자만 AI start command를 한 번 호출한다.
6. 만료 시각과 DB 현재 시각이 같거나 더 이른 응답은 sweeper 실행 전에도 거부되고 AI를 호출하지 않는다.
7. conditional update 0건은 현재 lifecycle snapshot을 다시 조회한 멱등 결과이며 새 외부 작업을 시작하지 않는다.
8. 잘못된 correlation 또는 소유자가 다른 요청은 lifecycle 정보를 노출하지 않고 상태를 변경하지 않는다.

## Review

| 리뷰 | 상태 | Reviewed At | Reviewed Commit | Notes |
|---|---|---|---|---|
| Scenario | Ready for Review | - | - | 신규 시나리오 리뷰 대기 |
| Assertion | Draft | - | - | 자동화 구현과 매핑 완료 후 assertion 리뷰 필요 |

## Automated Test Mapping

| 테스트 클래스 | 테스트 메서드 | 검증하는 기대 결과 |
|---|---|---|
| TBD | confirmation workflow and policy reevaluation tests | Then 1~3, 5, 7, 8 |
| TBD | conditional update expiration boundary tests | Then 4, 6, 7 |
| TBD | concurrent YES/NO/expiration race tests | Then 4, 5 |

## Execution Command

구현 후 실제 confirmation과 concurrency test suite 이름으로 교체한다.

```sh
./gradlew test --tests '*returnedmessage*Confirmation*' --rerun-tasks
```

## Execution Evidence

이 TC의 실행 결과는 상위 Verification의 최신 전체 실행 증적에서 관리한다. TC 문서에는 실행 메타데이터를 중복 기록하지 않는다.

- Latest Verification Execution: [상위 Verification 문서](../README.md#last-verification-execution)
- TC 결과 확인: 연결된 execution evidence에서 `Automated Test Mapping`에 해당하는 테스트 결과를 확인한다.

## Cleanup / Postconditions

- lifecycle row와 생성된 test execution correlation을 rollback 또는 삭제한다.
- executor를 종료하고 모든 latch를 해제하며 테스트마다 제한 시간을 둔다.

## Limitations

- 제한된 경쟁 조합과 실행 횟수로 핵심 불변식을 검증하며 가능한 모든 thread/DB interleaving을 증명하지 않는다.
- AI start 이후의 timeout, retry와 reconciliation은 Decision 004 범위다.
