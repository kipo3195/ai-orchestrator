# TC-005. Sweeper와 non-terminal 복구

| 항목 | 값 |
|---|---|
| Verification | [Returned Message Lifecycle Persistence Verification](../README.md) |
| Test Level | Integration / Concurrency |

Required 여부, 시나리오 리뷰 상태, 자동화 상태, Assertion 리뷰 상태와 `Last Verification Execution` 기준 실행 결과는 [상위 Verification 문서](../README.md)에서 관리한다. 이 문서의 `Scenario Review`와 `Assertion Review`는 각 최신 리뷰 증적을, `Last Execution`은 해당 TC의 최근 단독 또는 전체 실행을 기록한다. 단독 실행만으로 상위 Verification의 상태를 변경하지 않는다.

## Verification Purpose

확인 대기와 다른 non-terminal lifecycle이 process 종료 후에도 유지되고, 여러 instance의 sweeper 또는 recovery worker가 DB 조건과 유한한 claim을 사용해 중복 상태 전이나 영구 점유 없이 복구하는지 검증한다.

## Requirement Traceability

| 구분 | 참조 | 관련 요구사항 |
|---|---|---|
| Decision | [Confirmation expiration](../../../decisions/003-returned-message-lifecycle-persistence.md#confirmation-expiration) | 여러 sweeper가 실행되어도 조건부 갱신 하나만 성공하고 scheduler 시점은 응답 유효성을 결정하지 않는다. |
| Decision | [Guarantee boundary](../../../decisions/003-returned-message-lifecycle-persistence.md#guarantee-boundary) | lifecycle 상태를 process 재시작 후 복원한다. |
| Engineering | [Recovery](../../../engineering/003-returned-message-lifecycle-processing.md#recovery) | 상태별 복구와 owner token 및 유한 lease 기반 claim을 정의한다. |
| Implementation | Planned | 구현 후 expiration sweeper, recovery worker, repository batch query와 claim에 매핑한다. |

## Risk Addressed

- process-local scheduler 종료 후 확인 대기 상태가 영구히 남거나 유실될 수 있다.
- 여러 instance가 같은 만료 또는 복구 작업을 중복 수행할 수 있다.
- 죽은 worker의 claim이 해제되지 않아 lifecycle이 복구 불가능해질 수 있다.
- batch 처리 중 이미 전이된 row 또는 아직 유효한 row를 잘못 만료시킬 수 있다.

## Test Level and Boundary

- 테스트 수준: Persistence/Application Integration 및 Concurrency
- 검증 대상: 만료 대상 batch 조회, conditional expiration, 상태별 recovery claim과 lease 인계
- 실제 사용 컴포넌트: DB schema, repository adapter, sweeper와 recovery entry point
- Test Double: 상태별 외부 Port의 재개 횟수 및 correlation 관찰용 Spy
- 검증 제외: 실제 wall-clock scheduler 주기, 장시간 backoff와 외부 시스템의 exactly-once

### Rationale

재시작 복구는 새 application 객체가 기존 DB 상태만으로 작업을 식별할 수 있어야 하며, multi-instance 경쟁과 claim lease는 실제 persistence 조건으로 검증해야 한다.

## Preconditions — Given

1. DB에 `DETECTED`, confirmation delivery `PENDING`인 `AWAITING_CONFIRMATION`, `PROCESSING`, `DELIVERY_PENDING` lifecycle이 저장되어 있다.
2. 만료된 대기 row, 아직 유효한 대기 row와 terminal row가 함께 있다.
3. 기존 process-local 객체와 scheduler reference를 공유하지 않는 새 worker instance가 있다.
4. 두 worker의 시작점을 맞추는 barrier 또는 latch가 있다.

## Test Data

| 항목 | 값 | 선택 이유 |
|---|---|---|
| 만료 row 수 | `batchSize + 1` | batch 경계와 후속 실행 확인 |
| 만료 시각 | DB 현재 시각 이전, 동일, 이후 | `<=` 만료 조건 경계 |
| claim | 유효 lease, 만료 lease, 서로 다른 owner token | 단일 소유와 인계 조건 |
| 상태 | 네 non-terminal 상태와 terminal 상태 | 상태별 복구 및 제외 범위 |

## Procedure — When

1. 한 sweeper로 첫 batch와 다음 batch를 순차 실행한다.
2. 두 sweeper가 같은 만료 row 집합을 동시에 처리한다.
3. 기존 process를 폐기하고 새 worker가 저장된 non-terminal lifecycle을 조회한다.
4. 두 recovery worker가 같은 row claim을 동시에 시도한다.
5. 유효 claim과 만료 claim에서 다른 owner가 인계를 시도한다.
6. 각 상태별로 저장된 correlation을 사용해 복구 동작을 재개한다.

## Expected Results — Then

1. `AWAITING_CONFIRMATION`이고 `confirmation_expires_at <= DB current time`인 row만 `EXPIRED`로 전이한다.
2. 한 번에 설정된 batch 크기를 넘겨 처리하지 않고 다음 실행에서 남은 row를 처리한다.
3. 두 sweeper가 경쟁해도 각 row의 만료 전이는 최대 한 번 성공한다.
4. 아직 유효한 row와 non-awaiting 또는 terminal row는 변경하지 않는다.
5. 새 worker는 process-local 상태 없이 DB의 non-terminal lifecycle을 찾을 수 있다.
6. 같은 recovery claim은 한 worker만 획득하고 유효 lease 동안 다른 worker가 외부 작업을 시작하지 않는다.
7. lease가 만료되면 다른 worker가 conditional claim으로 인계할 수 있다.
8. 복구는 기존 selection, confirmation, execution 또는 delivery correlation을 유지하며 새 lifecycle이나 correlation을 만들지 않는다.

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
| TBD | expiration batch and concurrent sweeper tests | Then 1~4 |
| TBD | restart discovery and recovery claim tests | Then 5~8 |

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

구현 후 실제 sweeper와 recovery integration suite 이름으로 교체한다.

```sh
./gradlew test --tests '*returnedmessage*Recovery*' --tests '*returnedmessage*Expiration*' --rerun-tasks
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

- lifecycle과 claim fixture를 rollback 또는 삭제한다.
- scheduler를 직접 대기하지 않고 entry point를 호출하며 executor가 있으면 종료한다.

## Limitations

- 실제 scheduler 지연, 장시간 process outage와 운영 규모의 batch 처리량은 검증하지 않는다.
- 외부 서비스가 idempotency 또는 status query를 제공하지 않을 때의 중복 delivery는 허용된 한계로 남는다.
