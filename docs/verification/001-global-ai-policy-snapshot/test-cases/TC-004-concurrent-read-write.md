# TC-004. 동시 읽기/갱신 시 Snapshot 원자성

| 항목 | 값 |
|---|---|
| Verification | [Global AI Policy Snapshot Unit Verification](../README.md) |
| Test Level | Unit |

Required 여부, 자동화 상태와 `Last Verification Execution` 기준 실행 결과는 [상위 Verification 문서](../README.md)에서 관리한다. 이 문서의 `Last Execution`은 이 TC의 최근 실행을 기록하며, 단독 실행만으로 상위 Verification의 상태를 변경하지 않는다.

## Verification Purpose

다수 reader가 현재 Global AI Policy snapshot을 조회하는 동안 snapshot 교체가 반복되더라도, 각 조회가 완전한 이전 snapshot 또는 완전한 새 snapshot만 반환하고 tenant 정책과 로딩 시각이 섞인 중간 상태를 노출하지 않는지 검증한다.

## Requirement Traceability

| 구분 | 참조 | 관련 요구사항 |
|---|---|---|
| Engineering | [Policy Immutable Snapshot Refresh — Snapshot Model](../../../engineering/001-policy-immutable-snapshot-refresh.md#snapshot-model) | Tenant별 Global Policy 전체를 생성 후 변경되지 않는 하나의 snapshot으로 관리한다. |
| Engineering | [Policy Immutable Snapshot Refresh — Atomic Publication](../../../engineering/001-policy-immutable-snapshot-refresh.md#atomic-publication) | 전체 정책으로 생성한 새 snapshot을 한 번에 게시하며, reader는 평가 시작 시 현재 snapshot을 한 번 조회해 사용한다. |
| Implementation | [GlobalAiPolicySnapshot](../../../../src/main/java/com/ucware/ai_orchestrator/policy/domain/GlobalAiPolicySnapshot.java) | 초기화 여부, 전체 tenant 정책과 로딩 시각을 하나의 immutable 값으로 표현한다. |
| Implementation | [InMemoryGlobalAiPolicyStore](../../../../src/main/java/com/ucware/ai_orchestrator/policy/infrastructure/memory/InMemoryGlobalAiPolicyStore.java) | `AtomicReference`를 통해 현재 snapshot 조회와 전체 교체를 제공한다. |

## Risk Addressed

- 동시 조회 중 tenant 정책이 부분적으로 갱신되면 하나의 요청이 서로 다른 시점의 정책 조합을 관찰하여 잘못 허용되거나 거절될 수 있다.
- 정책 값과 `loadedAt`이 서로 다른 snapshot에서 읽히면 실제 적용 중인 정책 버전을 잘못 판단하여 장애 분석과 운영 판단을 왜곡할 수 있다.
- 새 snapshot 게시 이후에도 최종 reference가 이전 상태에 머물면 정책 변경 반영이 누락될 수 있다.

## Test Level and Boundary

- 테스트 수준: Unit
- 검증 대상: `InMemoryGlobalAiPolicyStore.currentSnapshot()`과 `replace(...)` 사이의 동시 읽기/교체 경계
- 실제 사용 컴포넌트: `GlobalAiPolicySnapshot`, `InMemoryGlobalAiPolicyStore`, Java `ExecutorService`와 `CountDownLatch`를 실제 객체로 사용한다.
- Test Double: 사용하지 않는다.
- 검증 제외: Policy DB와 JDBC mapping, `PolicySnapshotRefresher`, Spring `@Scheduled` 실행, 실제 요청 처리 흐름, 여러 AI Orchestrator instance 사이의 동기화

### Rationale

부분 상태 노출 위험은 현재 snapshot reference를 읽고 교체하는 in-memory Store 경계에서 발생하므로 실제 Store와 immutable Domain snapshot을 동시에 실행하는 단위 테스트로 직접 검증할 수 있다. Repository 조회 결과로 완전한 snapshot을 생성하는 동작은 TC-002에서 별도로 검증하며, 이 TC는 외부 시스템과 scheduler를 제외해 원자적 publication 책임에 집중한다. 모든 작업은 준비 완료 후 하나의 시작 latch로 해제하여 임의의 대기 시간에 의존하지 않고 읽기와 교체가 겹칠 기회를 만든다.

## Preconditions — Given

1. Store에는 `tenant-a=ON`, `tenant-b=OFF`, `loadedAt=2026-01-01T00:00:00Z`인 이전 snapshot이 게시되어 있다.
2. 교체 대상은 `tenant-a=OFF`, `tenant-b=ON`, `loadedAt=2026-01-01T00:05:00Z`인 새 snapshot이다.
3. 8개 reader와 1개 writer가 고정 크기 thread pool에 제출되고, 모든 작업이 준비될 때까지 시작 latch에서 대기한다.

## Test Data

| 항목 | 값 | 선택 이유 |
|---|---|---|
| 이전 snapshot | `tenant-a=true`, `tenant-b=false`, `loadedAt=2026-01-01T00:00:00Z` | 두 tenant의 상태와 로딩 시각을 새 snapshot과 모두 다르게 하여 상태 혼합 여부를 식별한다. |
| 새 snapshot | `tenant-a=false`, `tenant-b=true`, `loadedAt=2026-01-01T00:05:00Z` | 이전 snapshot과 반대인 정책 조합 및 다른 시각을 사용해 완전한 교체 여부를 확인한다. |
| Reader 수 | 8 | 여러 요청이 동일 Store를 동시에 조회하는 상황을 재현한다. |
| 반복 횟수 | reader별 2,000회 조회, writer 2,000회 교체 후 새 snapshot 최종 게시 | 제한된 실행 시간 안에서 읽기와 교체가 겹칠 기회를 충분히 만든다. |
| 동기화 제한 시간 | 준비 및 각 작업 완료에 각각 5초 | 교착이나 작업 미완료를 테스트 실패로 명확히 판정한다. |

## Procedure — When

1. 이전 snapshot을 Store에 게시한다.
2. 8개 reader 작업과 1개 writer 작업을 thread pool에 제출하고 각 작업이 준비됐음을 `ready` latch에 기록한다.
3. 모든 작업이 5초 안에 준비되면 `start` latch를 해제한다.
4. 각 reader는 Store에서 snapshot을 한 번씩 조회하는 동작을 2,000회 수행하고, 매 조회 결과가 이전 또는 새 snapshot과 완전히 같은지 즉시 검증한다.
5. Writer는 이전 snapshot과 새 snapshot을 번갈아 2,000회 교체한 뒤 새 snapshot을 마지막으로 게시한다.
6. 모든 작업이 각각 5초 안에 완료되는지 기다린 후 Store의 최종 snapshot을 조회한다.

## Expected Results — Then

1. 8개 reader와 1개 writer가 모두 5초 안에 준비되어 동시 실행을 시작한다.
2. Reader가 관찰한 모든 값은 tenant 정책과 `loadedAt`을 포함하여 완전한 이전 snapshot 또는 완전한 새 snapshot과 같다.
3. 이전 snapshot과 새 snapshot의 필드가 섞인 중간 상태는 관찰되지 않으며, reader 또는 writer 작업에서 assertion 실패나 예외가 발생하지 않는다.
4. 모든 작업은 제한 시간 안에 완료된다.
5. 동시 작업 종료 후 Store는 writer가 마지막으로 게시한 새 snapshot instance를 반환한다.

## Automated Test Mapping

| 테스트 클래스 | 테스트 메서드 | 검증하는 기대 결과 |
|---|---|---|
| [InMemoryGlobalAiPolicyStoreTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/memory/InMemoryGlobalAiPolicyStoreTests.java) | `concurrentReadersObserveOnlyCompleteSnapshotsDuringReplacement` | Then 1~5 |

## Execution Command

```sh
./gradlew test \
  --tests 'com.ucware.ai_orchestrator.policy.infrastructure.memory.InMemoryGlobalAiPolicyStoreTests.concurrentReadersObserveOnlyCompleteSnapshotsDuringReplacement' \
  --rerun-tasks
```

## Last Execution

| 항목 | 값 |
|---|---|
| Executed At | 2026-10-13 17:02 KST |
| Commit | `9980610` |
| Working Tree | Dirty |
| Environment | Java 17.0.19, Gradle 9.7.1 |
| Command | `./gradlew test --tests '*policy.*' --tests '*ConversationStartServiceTests' --rerun-tasks` |
| Execution Scope | Verification |
| Parent Execution / Evidence | [Last Verification Execution](../README.md#last-verification-execution) |
| Result | Passed |
| Passed / Failed / Skipped | 1 / 0 / 0 |

## Cleanup / Postconditions

- `finally`에서 시작 latch를 해제하고 executor에 `shutdownNow()`를 호출하여 테스트 실패 시에도 대기 중인 작업과 thread pool을 정리한다.
- 외부 시스템이나 영속 데이터를 사용하지 않으므로 별도 데이터 정리는 필요하지 않다.
- 정상 완료 시 Store는 새 snapshot을 현재 값으로 유지한다.

## Limitations

- 이 테스트는 정해진 thread 수와 반복 횟수에서 동시 실행을 재현하는 회귀 테스트이며 가능한 모든 JVM thread interleaving을 증명하지 않는다.
- Writer의 반복 교체는 읽기와 교체가 겹칠 기회를 늘리기 위한 테스트 구성으로, 운영 scheduler의 실행 주기나 성능을 재현하지 않는다.
- 각 reader 조회가 이전 또는 새 snapshot 중 어느 것을 반환할지는 thread scheduling에 따라 달라지므로 두 상태가 모두 관찰될 것을 요구하지 않는다.
- 실제 refresh가 repository 결과로 snapshot을 생성하는 과정, Spring scheduling과 여러 application instance 사이의 eventual consistency는 검증하지 않는다.
