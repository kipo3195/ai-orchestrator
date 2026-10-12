# Global AI Policy Snapshot Unit Verification

| 항목 | 값 |
|---|---|
| Status | In Progress |
| Last Verified At | - |
| Verified Commit | - |
| Stale Since | - |
| Stale Reason | - |

이 문서는 해당 Verification의 상태, TC별 자동화 상태와 실행 결과의 단일 원천이다. 상태 정의와 전환 규칙은 [Verification 상태 관리](../README.md#상태-관리)를 따른다.

## Objective

Tenant별 Global AI Policy가 초기화 전에는 fail-closed로 동작하고, 정상 갱신 시 전체 정책 snapshot이 원자적으로 교체되는지 검증한다. 다수 요청의 정책 조회와 snapshot 갱신이 동시에 발생하더라도 각 요청에는 완전한 이전 snapshot 또는 완전한 새 snapshot만 노출되고 부분 갱신 상태가 관찰되지 않아야 한다. 또한 갱신 실패 시 마지막 정상 snapshot을 유지하며, AI 호출 직전의 정책 평가 결과에 따라 비활성화된 tenant의 실행이 차단되는지 검증한다.

## Verification Basis

| 구분 | 참조 | 이 검증과의 관계 |
|---|---|---|
| Engineering | [Policy Immutable Snapshot Refresh](../../engineering/001-policy-immutable-snapshot-refresh.md) | Snapshot 초기 상태, 불변성, 원자적 교체, 실패 처리와 테스트 기준을 정의한다. |
| Decision | [Policy Persistence Access](../../decisions/001-policy-persistence-access.md) | Tenant별 Global Policy 조회와 row가 없는 tenant의 기본 정책을 정의한다. |
| Implementation | [GlobalAiPolicySnapshot](../../../src/main/java/com/ucware/ai_orchestrator/policy/domain/GlobalAiPolicySnapshot.java) | 초기화 여부, tenant별 활성화 상태와 로딩 시각을 표현하는 검증 대상이다. |
| Implementation | [InMemoryGlobalAiPolicyStore](../../../src/main/java/com/ucware/ai_orchestrator/policy/infrastructure/memory/InMemoryGlobalAiPolicyStore.java) / [PolicySnapshotRefresher](../../../src/main/java/com/ucware/ai_orchestrator/policy/infrastructure/scheduling/PolicySnapshotRefresher.java) | 현재 snapshot 보관, 전체 교체와 refresh 실패 시 상태 유지 책임을 검증한다. |
| Implementation | [AiExecutionPolicyService](../../../src/main/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyService.java) / [ConversationStartService](../../../src/main/java/com/ucware/ai_orchestrator/conversationstart/application/ConversationStartService.java) | Tenant 정책 판단과 AI 호출 직전 실행 차단 책임을 검증한다. |

## Scope

- 초기화되지 않은 Global AI Policy snapshot에서 AI 실행이 fail-closed 처리된다.
- 정상 refresh 결과가 전체 tenant 정책을 포함하는 새 snapshot으로 게시된다.
- Snapshot 교체 전후에 일부 tenant 정책만 반영된 중간 상태가 노출되지 않는다.
- 정책 조회와 snapshot 갱신이 동시에 발생해도 독자는 완전한 이전 snapshot 또는 완전한 새 snapshot만 관찰한다.
- Refresh 실패 시 초기 상태 또는 마지막 정상 snapshot을 유지한다.
- Tenant 정책이 없거나 OFF이면 AI 실행을 거절하고, ON이면 허용한다.
- 예약된 실행은 AI 호출 직전에 정책을 다시 평가하며, 정책이 OFF이면 AI port를 호출하지 않는다.

## Out of Scope

- 실제 Policy DB의 SQL, schema, JDBC row mapping과 운영 DB 호환성 검증
- Spring `@Scheduled` 등록, refresh 주기 설정 바인딩과 실제 시간 경과에 따른 실행 검증
- 실제 HTTP 기반 AI adapter와의 통신 검증
- Feature Policy와 User Policy의 조회, 캐시와 평가 흐름
- 여러 AI Orchestrator instance 사이의 snapshot 동기화와 distributed lock

## Test Strategy

Snapshot 모델과 정책 분기는 단위 테스트로 검증한다. Repository, scheduler와 AI port는 Test Double로 대체하여 refresh와 정책 평가를 외부 시스템 없이 결정적으로 실행한다. 시간 값은 고정된 `Clock`으로 제어한다. 동시성 테스트는 제어 가능한 동기화 지점을 사용해 읽기와 교체를 겹치게 하고, 임의의 대기 시간에 의존하지 않도록 구성한다. 실제 JDBC, Spring scheduling과 HTTP adapter 연결은 이 검증의 필수 단위 테스트 범위에서 제외하고 후속 통합 테스트 경계로 관리한다.

## Completion Criteria

이 검증은 다음 조건을 만족해야 한다.

1. 초기화 전, tenant 정책 누락과 OFF 상태에서 AI 실행이 차단된다.
2. Refresh 실패 후에도 마지막 정상 snapshot이 유지된다.
3. 다수 요청이 동시에 조회하는 동안 snapshot을 갱신해도 완전한 이전 상태 또는 완전한 새 상태만 관찰된다.
4. 정책이 AI 호출 전에 OFF로 변경되면 AI port가 호출되지 않는다.

공통 완료 기준은 [Verification 공통 완료 기준](../README.md#공통-완료-기준)을 따른다.

## Test Summary

| TC ID | 검증 항목 | 테스트 수준 | Required | 자동화 상태 | 실행 결과 |
|---|---|---|---|---|---|
| TC-001 | [초기화 전 Snapshot 상태](test-cases/TC-001-uninitialized-snapshot.md) | Unit | Yes | Partial | Not Run |
| TC-002 | [Snapshot 전체 교체 및 불변성](test-cases/TC-002-snapshot-replacement.md) | Unit | Yes | Partial | Not Run |
| TC-003 | [Tenant 정책 판단 및 AI 호출 차단](test-cases/TC-003-tenant-policy-evaluation.md) | Unit | Yes | Automated | Not Run |
| TC-004 | [동시 읽기/갱신 안전성](test-cases/TC-004-concurrent-read-write.md) | Unit | Yes | Planned | Not Run |

## Execution Guide

공통 실행 방법과 결과 위치는 [Verification 공통 실행 가이드](../README.md#공통-실행-가이드)를 따른다.

### Prerequisites

- 단위 테스트 실행에 필요한 외부 시스템은 없다.

### Verification Tests

이 명령은 이 Verification의 모든 Required TC 자동화 테스트를 포함한다.

```sh
./gradlew test \
  --tests '*policy.*' \
  --tests '*ConversationStartServiceTests' \
  --rerun-tasks
```

## Last Execution

| 항목 | 값 |
|---|---|
| Executed At | - |
| Verified Commit | - |
| Working Tree | - |
| Environment | - |
| Command | - |
| Result | Not Run |
| Passed / Failed / Skipped | - |

## Evidence

- Execution Summary: -
- CI Run: -

`Last Execution`을 기본 실행 증적으로 사용한다. 별도 실행 요약, 로그 또는 CI 결과가 필요한 경우에만 위 항목에 연결한다.

## Known Risks and Gaps

| 항목 | 영향 | 현재 판단 | 대응 또는 추적 위치 |
|---|---|---|---|
| `enabled=NULL`이 JDBC에서 `false`로 해석될 수 있음 | 필수 null 거절 규칙과 달리 잘못된 정책 값이 OFF 상태로 게시될 수 있다. | 확인 및 수정 필요 | JDBC 통합 테스트와 [Policy Immutable Snapshot Refresh](../../engineering/001-policy-immutable-snapshot-refresh.md)의 publication validation 기준 |
| Snapshot 전체 교체와 collection 불변성 테스트가 불완전함 | 제거된 tenant가 남거나 외부 변경으로 현재 정책이 변하는 결함을 발견하지 못할 수 있다. | Required TC 보강 필요 | [TC-002](test-cases/TC-002-snapshot-replacement.md) |
| 동시 읽기/갱신 테스트가 구현되지 않음 | 부분 상태 또는 비원자적 게시 결함에 대한 실행 증적이 없다. | Required TC 구현 필요 | [TC-004](test-cases/TC-004-concurrent-read-write.md) |
| TC별 Given/When/Then과 자동화 테스트 매핑이 미완성임 | 테스트 코드와 보장하려는 동작 사이의 추적성이 부족하다. | 문서 보강 필요 | `test-cases/` |

## Unverified Boundaries and Follow-up Tests

| 경계 | 검증할 내용 | 현재 테스트로 부족한 이유 | Required | 우선순위 |
|---|---|---|---|---|
| JDBC → DB → refresh → store | 실제 테이블에서 ON/OFF/삭제를 읽어 전체 교체하고, 중복·빈 tenant와 DB 장애 시 기존 snapshot을 유지하는지 검증한다. | Mock Map으로는 SQL, 컬럼 매핑, DB 제약과 예외 전달을 확인할 수 없다. H2는 빠른 연결 검증용이며 운영 DB 호환성은 같은 DB 종류로 검증해야 한다. | No | P1 |
| SQL NULL → 정책 상태 | `enabled=NULL` 입력을 거절하고 현재 snapshot을 유지하는지 검증한다. | 현재 단위 테스트는 JDBC `getBoolean`과 `wasNull` 처리 여부를 확인하지 않는다. | No | P1 |
| Spring 설정·scheduler → refresh | 실제 bean 연결, refresh 주기 바인딩, 양수가 아닌 설정 거절과 scheduled 진입점의 갱신을 검증한다. | 직접 메서드 호출은 `@Scheduled` 등록과 설정 바인딩을 검증하지 않는다. | No | P2 |
| 요청 → 예약 실행 → 정책 → AI adapter | DB에서 정책을 OFF로 변경하고 refresh한 뒤 예약 작업이 REJECTED가 되며 HTTP stub 요청이 발생하지 않는지 검증한다. ON이면 호출과 결과 전달도 확인한다. | Mock Runnable과 AI port로는 실제 scheduler, tenant routing과 HTTP adapter 연결을 확인할 수 없다. | No | P1 |

## Overall Assessment

- Assessment Scope: Unit Scope
- Basis: Required TC가 모두 구현되지 않았고 동일 commit에서 실행한 결과와 증적이 없다.

### Verified Behaviors

- None
<!-- 예시)
- 초기화되지 않은 snapshot에서는 AI 실행이 `GLOBAL_POLICY_UNAVAILABLE` 사유로 거절된다.
- Tenant 정책이 없거나 OFF이면 AI 실행이 거절되고, ON이면 허용된다.
- Refresh가 실패하면 마지막 정상 snapshot이 유지된다.
- 다수 요청이 조회하는 중 snapshot을 교체해도 완전한 이전 snapshot 또는 완전한 새 snapshot만 관찰된다.
- AI 호출 직전에 tenant 정책이 OFF이면 실행 상태가 `REJECTED`가 되고 AI port는 호출되지 않는다. -->

### Remaining Limitations

- TC-001과 TC-002는 일부 동작만 자동화되어 있다.
- TC-004 동시 읽기/갱신 테스트가 구현되지 않았다.
- JDBC, Spring scheduling과 실제 AI adapter 연결 경계는 검증하지 않았다.
- 실행 commit과 테스트 결과 증적이 없다.

### Conclusion

현재는 검증 범위, 테스트 전략과 필수 TC를 정의하는 단계다. 모든 Required TC를 구현하고 동일 commit에서 실행한 결과와 증적을 기록하기 전까지 Global AI Policy snapshot 동작을 `Verified`로 판정하지 않는다.
