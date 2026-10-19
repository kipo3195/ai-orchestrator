# TC-007. Transaction과 계층 경계

| 항목 | 값 |
|---|---|
| Verification | [Returned Message Lifecycle Persistence Verification](../README.md) |
| Test Level | Architecture / Integration |

Required 여부, 자동화 상태와 `Last Verification Execution` 기준 실행 결과는 [상위 Verification 문서](../README.md)에서 관리한다. 이 문서의 `Last Execution`은 해당 TC의 최근 단독 또는 전체 실행을 기록하며, 단독 실행만으로 상위 Verification의 상태를 변경하지 않는다.

## Verification Purpose

Lifecycle insert와 각 상태 변경이 짧은 local transaction으로 끝나고, Message Query, Policy/Permission 조회, confirmation 전송, AI command와 result delivery 중에는 DB transaction이나 row lock을 유지하지 않으며 JDBC 세부사항이 Infrastructure 밖으로 누출되지 않는지 검증한다.

## Requirement Traceability

| 구분 | 참조 | 관련 요구사항 |
|---|---|---|
| Decision | [Transaction boundary](../../../decisions/003-returned-message-lifecycle-persistence.md#transaction-boundary) | lifecycle insert와 conditional update만 짧은 transaction으로 실행하고 외부 I/O 전후를 분리한다. |
| Decision | [Decision](../../../decisions/003-returned-message-lifecycle-persistence.md#decision) | Application/Domain에는 기술 독립적인 repository interface를 두고 JDBC와 SQL을 Infrastructure에 둔다. |
| Engineering | [Transaction Boundary](../../../engineering/003-returned-message-lifecycle-processing.md#transaction-boundary) | 각 외부 작업 중 transaction을 유지하지 않고 crash window를 상태별 복구로 처리한다. |
| Implementation | Planned | 구현 후 package dependency rule, Spring transaction boundary와 external Port 호출에 매핑한다. |

## Risk Addressed

- 느린 외부 서비스 호출이 DB connection과 row lock을 장시간 점유할 수 있다.
- 외부 호출과 DB write를 하나의 transaction처럼 취급해 timeout과 재시도 시 중복 작업이 발생할 수 있다.
- Application/Domain이 JDBC exception, SQL 또는 persistence row에 결합될 수 있다.

## Test Level and Boundary

- 테스트 수준: Architecture 및 Spring Integration
- 검증 대상: package dependency, transaction 활성 구간과 external Port 호출 시점
- 실제 사용 컴포넌트: application use case, Spring transaction manager, repository adapter와 DB
- Test Double: 호출 시 transaction 활성 여부를 기록하고 완료를 제어하는 blocking external Port Probe
- 검증 제외: 운영 connection pool 크기, network timeout 수치와 분산 transaction 제품

### Rationale

정적 architecture test로 의존 방향을 검증하고, external Port 내부에서 Spring transaction 활성 여부를 관찰하는 integration test로 runtime transaction 경계를 확인한다. 필요하면 외부 호출이 block된 동안 별도 connection의 DB 작업이 완료되는지도 보조 assertion으로 확인한다.

## Preconditions — Given

1. Spring transaction manager와 실제 test database를 사용하는 application context가 있다.
2. 호출 시점의 transaction 활성 여부를 기록하고 latch에서 대기할 수 있는 외부 Port Probe가 있다.
3. Application/Domain/Infrastructure package 의존성을 검사할 수 있는 architecture test가 있다.

## Test Data

| 항목 | 값 | 선택 이유 |
|---|---|---|
| 외부 Port | Message Query, confirmation sender, AI gateway, result sender | Decision이 명시한 모든 외부 I/O 범위 |
| local DB 작업 | lifecycle insert와 conditional update | transaction이 허용되는 범위 |
| 병렬 DB 작업 | 별도 lifecycle 조회/갱신 | 외부 호출 중 lock 점유 여부 보조 확인 |

## Procedure — When

1. lifecycle insert 또는 conditional update를 수행한 뒤 각 외부 Port 진입점에서 실행을 중지한다.
2. Port Probe가 관찰한 Spring transaction 상태를 확인한다.
3. 외부 Port가 대기하는 동안 별도 connection에서 독립 lifecycle DB 작업을 수행한다.
4. package dependency architecture test를 실행한다.
5. repository가 반환하는 결과와 exception이 기술 독립적인 application 결과로 변환되는지 확인한다.

## Expected Results — Then

1. lifecycle insert와 각 conditional update는 각각 local transaction에서 완료된다.
2. 모든 외부 Port 진입 시 actual Spring transaction은 비활성 상태다.
3. 외부 Port가 대기하는 동안 관련 row lock이나 connection 점유 때문에 독립 DB 작업이 불필요하게 block되지 않는다.
4. Application과 Domain은 `JdbcClient`, JDBC exception, SQL, persistence row와 schema package에 의존하지 않는다.
5. Infrastructure만 SQL, row mapping과 unique constraint exception 해석을 담당한다.
6. 외부 Port 실패는 이미 commit된 이전 상태를 rollback하는 분산 transaction으로 처리되지 않는다.

## Automated Test Mapping

| 테스트 클래스 | 테스트 메서드 | 검증하는 기대 결과 |
|---|---|---|
| TBD | package dependency architecture tests | Then 4, 5 |
| TBD | external Port transaction probe integration tests | Then 1~3, 6 |

## Execution Command

구현 후 실제 architecture와 transaction integration suite 이름으로 교체한다.

```sh
./gradlew test --tests '*returnedmessage*Architecture*' --tests '*returnedmessage*Transaction*' --rerun-tasks
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

- blocking Port Probe의 latch를 `finally`에서 해제하고 executor를 종료한다.
- DB fixture는 rollback 또는 삭제하고 대기 transaction이 남지 않게 한다.

## Limitations

- transaction 활성 여부와 제한된 병렬 작업으로 경계를 검증하며 운영 부하에서의 lock wait와 pool 고갈을 측정하지 않는다.
- 특정 framework annotation의 존재보다 관찰 가능한 transaction 경계를 우선 검증한다.
