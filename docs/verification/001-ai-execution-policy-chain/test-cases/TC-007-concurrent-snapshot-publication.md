# TC-007. 동시 snapshot 게시

| 항목 | 값 |
|---|---|
| Verification | [AI Execution Policy Chain Verification](../README.md) |
| Test Level | Unit |

## Verification Purpose

다수 reader가 현재 snapshot을 조회하는 동안 writer가 Global/Feature snapshot을 반복 교체해도 각 reader가 완전한 이전 또는 새 snapshot만 관찰하는지 검증한다.

## Requirement Traceability

| 구분 | 참조 | 관련 요구사항 |
|---|---|---|
| Engineering | [Policy Immutable Snapshot Refresh — Snapshot Model](../../../engineering/001-policy-immutable-snapshot-refresh.md#snapshot-model) | Global/Feature 전체를 생성 후 변경되지 않는 하나의 snapshot으로 관리한다. |
| Engineering | [Policy Immutable Snapshot Refresh — Atomic Publication](../../../engineering/001-policy-immutable-snapshot-refresh.md#atomic-publication) | 새 snapshot reference를 한 번에 교체한다. |
| Implementation | [InMemoryAiPolicyStore](../../../../src/main/java/com/ucware/ai_orchestrator/policy/infrastructure/memory/InMemoryAiPolicyStore.java) | `AtomicReference<AiPolicySnapshot>`로 current snapshot을 제공한다. |

## Risk Addressed

- 하나의 요청이 이전 Global과 새 Feature를 조합할 수 있다.
- 정책 상태와 `loadedAt`이 서로 다른 snapshot에서 관찰될 수 있다.
- Writer의 최종 게시 이후 Store가 이전 reference를 유지할 수 있다.

## Test Level and Boundary

- 테스트 수준: Concurrency Unit
- 검증 대상: `InMemoryAiPolicyStore.currentSnapshot()`과 `replace(...)` 사이의 동시 접근
- 실제 사용 컴포넌트: Store, immutable snapshot, `ExecutorService`, `CountDownLatch`
- Test Double: 사용하지 않는다.
- 검증 제외: Repository refresh, Spring scheduler와 여러 application instance 사이의 동기화

### Rationale

부분 상태 노출 위험은 process-local snapshot reference 경계에 있으므로 실제 Store를 여러 thread에서 반복 조회·교체하는 단위 테스트로 직접 검증한다.

## Preconditions — Given

1. Global, Feature와 `loadedAt`이 서로 다른 이전 snapshot과 새 snapshot이 있다.
2. 8개 reader와 1개 writer가 고정 thread pool에서 동일한 시작 latch를 기다린다.

## Test Data

| 항목 | 값 | 선택 이유 |
|---|---|---|
| 이전 snapshot | Global ON, Feature OFF, `00:00:00Z` | 새 snapshot과 모든 관찰값을 구분 |
| 새 snapshot | Global OFF, Feature ON, `00:05:00Z` | 필드 혼합 여부 식별 |
| Reader 수 | 8 | 여러 동시 요청 상황 재현 |
| 반복 횟수 | Reader/Writer 각각 2,000회 | 제한된 시간 안에 interleaving 가능성 확대 |
| 제한 시간 | 준비 및 완료 각각 5초 | 교착과 미완료를 명확한 실패로 판정 |

## Procedure — When

1. 이전 snapshot을 Store에 게시한다.
2. Reader와 writer 작업을 제출하고 모든 작업이 준비될 때까지 기다린다.
3. 시작 latch를 해제한다.
4. Reader는 current snapshot을 반복 조회하고 writer는 이전/새 snapshot을 반복 교체한다.
5. Writer가 새 snapshot을 마지막으로 게시한 뒤 모든 작업 완료와 최종 상태를 확인한다.

## Expected Results — Then

1. 모든 작업이 제한 시간 안에 준비되고 완료된다.
2. Reader가 관찰한 모든 reference는 완전한 이전 또는 새 snapshot이다.
3. 두 snapshot 필드가 섞인 중간 상태는 관찰되지 않는다.
4. 작업 종료 후 current snapshot은 writer가 마지막으로 게시한 새 snapshot이다.

## Review

| 리뷰 | 상태 | Reviewed At | Reviewed Commit | Notes |
|---|---|---|---|---|
| Scenario | Ready for Review | - | - | 신규 시나리오 리뷰 대기 |
| Assertion | Ready for Review | - | - | 신규 assertion 리뷰 대기 |

## Automated Test Mapping

| 테스트 클래스 | 테스트 메서드 | 검증하는 기대 결과 |
|---|---|---|
| [InMemoryAiPolicyStoreTests](../../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/memory/InMemoryAiPolicyStoreTests.java) | `concurrentReadersObserveOnlyCompleteSnapshotsDuringReplacement` | Then 1~4 |

## Execution Command

```sh
./gradlew test \
  --tests 'com.ucware.ai_orchestrator.policy.infrastructure.memory.InMemoryAiPolicyStoreTests.concurrentReadersObserveOnlyCompleteSnapshotsDuringReplacement' \
  --rerun-tasks
```

## Execution Evidence

이 TC의 실행 결과는 상위 Verification의 최신 전체 실행 증적에서 관리한다. TC 문서에는 실행 메타데이터를 중복 기록하지 않는다.

- Latest Verification Execution: [상위 Verification 문서](../README.md#last-verification-execution)
- TC 결과 확인: 연결된 execution evidence에서 `Automated Test Mapping`에 해당하는 테스트 결과를 확인한다.

## Cleanup / Postconditions

- `finally`에서 start latch를 해제하고 executor에 `shutdownNow()`를 호출한다.
- 정상 완료 후 Store는 새 snapshot을 현재 값으로 유지한다.

## Limitations

- 제한된 thread 수와 반복 횟수의 회귀 테스트이며 가능한 모든 JVM interleaving을 증명하지 않는다.
- 여러 Orchestrator instance 사이의 snapshot 일관성은 검증하지 않는다.
