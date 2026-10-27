# TC-002. Lifecycle 생성 멱등성과 격리

| 항목 | 값 |
|---|---|
| Verification | [Returned Message Lifecycle Persistence Verification](../README.md) |
| Test Level | Persistence Integration / Concurrency |

## Verification Purpose

동일 Trigger retry와 동일 복귀 구간의 중복 전달이 순차 또는 동시에 발생해도 DB 제약에 의해 lifecycle 하나만 생성되고, 정상 중복·계약 충돌·tenant 격리가 정확히 구분되는지 검증한다.

## Requirement Traceability

| 구분 | 참조 | 관련 요구사항 |
|---|---|---|
| Decision | [Lifecycle creation idempotency](../../../decisions/003-returned-message-lifecycle-persistence.md#lifecycle-creation-idempotency) | 두 tenant-scoped unique constraint와 충돌별 처리 및 정보 비노출을 정의한다. |
| Engineering | [Lifecycle Creation](../../../engineering/003-returned-message-lifecycle-processing.md#lifecycle-creation) | 생성됨, 기존 lifecycle, correlation 충돌과 저장소 실패의 application 결과를 정의한다. |
| Implementation | Planned | 구현 후 migration, JDBC repository adapter와 lifecycle creation use case에 매핑한다. |

## Risk Addressed

- REST retry 또는 여러 instance의 동시 처리로 lifecycle과 최초 Message Query가 중복될 수 있다.
- 같은 Trigger가 다른 user나 absence period에 재사용되어 잘못된 lifecycle을 반환할 수 있다.
- unique 충돌 처리 중 다른 tenant 또는 user의 lifecycle 정보가 노출될 수 있다.
- process-local lock 테스트만 통과하고 실제 DB 경쟁에서는 중복 row가 생성될 수 있다.

## Test Level and Boundary

- 테스트 수준: Persistence Integration 및 multi-thread/multi-instance Concurrency
- 검증 대상: schema unique constraint, JDBC insert와 충돌 해석, tenant-scoped 기존 row 조회
- 실제 사용 컴포넌트: 운영과 같은 계열의 RDB, migration/schema, JDBC repository adapter
- Test Double: 사용하지 않거나 application side effect 관찰에만 사용한다.
- 검증 제외: 외부 Message Query의 실제 구현과 운영 부하 규모

### Rationale

이 보장은 DB constraint와 실제 concurrent insert에 의존하므로 Mock repository나 단일 thread 단위 테스트만으로 확인할 수 없다.

## Preconditions — Given

1. 두 unique constraint가 적용된 격리된 test database가 있다.
2. 둘 이상의 독립 repository/application 실행 주체가 같은 DB를 사용한다.
3. concurrent insert 시작점을 맞추는 barrier 또는 latch가 있다.

## Test Data

| 항목 | 값 | 선택 이유 |
|---|---|---|
| 기준 correlation | tenant-1, user-1, period-1, trigger-1 | 최초 생성과 retry 기준 |
| 같은 period, 다른 Trigger | tenant-1, user-1, period-1, trigger-2 | absence period constraint 충돌 |
| 같은 Trigger, 다른 user | tenant-1, user-2, period-2, trigger-1 | 정상 retry가 아닌 계약 충돌 |
| 같은 Trigger, 다른 period | tenant-1, user-1, period-2, trigger-1 | correlation 변조 또는 upstream 오류 |
| 다른 tenant | tenant-2, user-1, period-1, trigger-1 | tenant 범위의 독립성 |

## Procedure — When

1. 기준 correlation을 최초 insert한다.
2. 동일 correlation, 같은 period의 다른 Trigger, 같은 Trigger의 다른 user/period를 순차 insert한다.
3. 같은 period 또는 Trigger를 사용하는 두 요청을 동시에 insert한다.
4. 같은 식별자를 다른 tenant에서 insert한다.
5. 각 충돌 결과와 기존 lifecycle 조회 범위를 확인한다.

## Expected Results — Then

1. 최초 요청은 `DETECTED` lifecycle 하나를 생성하고 생성 성공 결과를 반환한다.
2. 동일 Trigger와 동일 correlation retry는 기존 lifecycle을 반환하고 새 row를 생성하지 않는다.
3. 같은 absence period의 다른 Trigger는 동일 복귀 구간 중복으로 처리한다.
4. 같은 tenant의 같은 Trigger가 다른 user 또는 period를 가리키면 계약 충돌이며 기존 lifecycle 내용을 반환하지 않는다.
5. 다른 tenant의 같은 Trigger와 period 값은 독립적으로 생성할 수 있다.
6. concurrent insert에서도 각 unique key별 row는 하나이고 최초 작업 수행 권한은 하나의 처리자만 얻는다.
7. DB timeout 또는 insert 실패 시 외부 작업을 시작하지 않는다.

## Review

| 리뷰 | 상태 | Reviewed At | Reviewed Commit | Notes |
|---|---|---|---|---|
| Scenario | Ready for Review | - | - | 신규 시나리오 리뷰 대기 |
| Assertion | Draft | - | - | 자동화 구현과 매핑 완료 후 assertion 리뷰 필요 |

## Automated Test Mapping

| 테스트 클래스 | 테스트 메서드 | 검증하는 기대 결과 |
|---|---|---|
| TBD | sequential duplicate and correlation conflict tests | Then 1~5, 7 |
| TBD | concurrent insert for same period/Trigger test | Then 6 |

## Execution Command

구현 후 실제 persistence integration suite 이름으로 교체한다.

```sh
./gradlew test --tests '*returnedmessage*LifecycleCreation*' --rerun-tasks
```

## Execution Evidence

이 TC의 실행 결과는 상위 Verification의 최신 전체 실행 증적에서 관리한다. TC 문서에는 실행 메타데이터를 중복 기록하지 않는다.

- Latest Verification Execution: [상위 Verification 문서](../README.md#last-verification-execution)
- TC 결과 확인: 연결된 execution evidence에서 `Automated Test Mapping`에 해당하는 테스트 결과를 확인한다.

## Cleanup / Postconditions

- 테스트마다 schema 또는 tenant fixture를 격리하고 생성 row를 rollback 또는 삭제한다.
- executor를 종료하고 latch 대기 작업이 남지 않도록 제한 시간을 둔다.

## Limitations

- 제한된 동시 실행 수로 중요한 race를 재현하는 회귀 테스트이며 모든 DB interleaving을 증명하지 않는다.
- DB 제품이 확정되기 전에는 최종 unique violation 분류와 isolation 동작을 판정할 수 없다.
