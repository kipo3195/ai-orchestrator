# [Verification Title]

| 항목 | 값 |
|---|---|
| Status | Draft |
| Last Verified At | - |
| Verified Commit | - |
| Stale Since | - |
| Stale Reason | - |

이 문서는 해당 Verification의 상태, TC별 자동화 상태와 `Last Verification Execution` 기준 실행 결과의 단일 원천이다. 상태 정의와 전환 규칙은 [Verification 상태 관리](../README.md#상태-관리)를 따른다.

## Objective

[이 검증이 보장하려는 동작과 방지하려는 핵심 위험을 한 문단으로 설명한다. 구현 수단보다 외부에서 관찰할 수 있는 동작과 도메인 의미를 중심으로 작성한다.]

## Verification Basis

| 구분 | 참조 | 이 검증과의 관계 |
|---|---|---|
| Engineering | [문서 제목](경로) | [검증할 설계 원칙 또는 요구사항] |
| Decision | [문서 제목](경로) | [선택한 방식과 제약] |
| Implementation | [구현 대상](경로) | [검증 대상 책임] |

## Scope

- [이번 verification에서 보장하려는 동작]
- [반드시 통과해야 하는 상태 전이 또는 경계]
- [오류 또는 예외 상황에서 기대하는 동작]

## Out of Scope

- [이번 verification에서 의도적으로 제외한 범위와 이유]
- [별도 verification 또는 후속 테스트에서 다룰 범위]

## Test Strategy

[선택한 테스트 수준과 그 이유를 설명한다. 단위 테스트, 통합 테스트, workflow 테스트가 각각 어떤 위험을 담당하는지와 Test Double을 사용하는 경계를 명시한다. 시간, 동시성, 외부 시스템처럼 비결정적 요소를 어떻게 제어하는지도 기록한다.]

## Completion Criteria

이 검증은 다음 조건을 만족해야 한다.

1. [검증 대상에 특화된 완료 조건]
2. [...]

공통 완료 기준은 [Verification 공통 완료 기준](../README.md#공통-완료-기준)을 따른다.

## Test Summary

<!--
자동화 상태: Planned | Partial | Automated | Manual
실행 결과: Not Run | Passed | Failed | Blocked
실행 결과는 Last Verification Execution에서 해당 TC가 얻은 결과를 기록한다.
-->

| TC ID | 검증 항목 | 테스트 수준 | Required | 자동화 상태 | 실행 결과 |
|---|---|---|---|---|---|
| TC-001 | [검증 항목](test-cases/TC-001-example.md) | Unit | Yes | Planned | Not Run |

## Execution Guide

### Prerequisites

- [필수 JDK 또는 Runtime 버전]
- [필요한 외부 시스템, 테스트 컨테이너 또는 환경 변수]
- 저장소 루트에서 실행한다.

### Verification Tests

이 명령은 해당 Verification의 모든 Required TC 자동화 테스트를 포함해야 한다.

```sh
./gradlew test --tests '[검증 대상 테스트 필터]' --rerun-tasks
```

### Full Regression

```sh
./gradlew test --rerun-tasks
```

### Result Locations

- HTML: `build/reports/tests/test/index.html`
- JUnit XML: `build/test-results/test/TEST-*.xml`
- CI: [실행 결과 링크]

## Last Verification Execution

<!-- 모든 Required TC를 동일 조건에서 함께 실행한 후 실제 값을 기록한다. 개별 TC 실행을 조합하지 않으며, 실행 전에는 '-' 또는 'Not Run'을 유지한다. -->

| 항목 | 값 |
|---|---|
| Executed At | - |
| Commit | - |
| Working Tree | - |
| Environment | - |
| Command | - |
| Required TC Coverage | - |
| Result | Not Run |
| Passed / Failed / Skipped | - |

## Evidence

- Execution Summary: -
- CI Run: -

`Last Verification Execution`을 검증 상태와 Test Summary 실행 결과의 기준으로 사용한다. 별도 실행 요약, 로그 또는 CI 결과가 필요한 경우에만 위 항목에 연결한다.

## Known Risks and Gaps

| 항목 | 영향 | 현재 판단 | 대응 또는 추적 위치 |
|---|---|---|---|
| [알려진 위험 또는 구현과 설계의 차이] | [실패 시 영향] | [수용, 미결정, 수정 필요 등] | [관련 문서, 이슈 또는 후속 TC] |

## Unverified Boundaries and Follow-up Tests

<!-- 현재 판정의 필수 범위가 아니라면 Required를 No로 표시한다. -->

| 경계 | 검증할 내용 | 현재 테스트로 부족한 이유 | Required | 우선순위 |
|---|---|---|---|---|
| [연결 경계] | [후속 검증 내용] | [Mock 또는 현재 테스트가 확인하지 못하는 부분] | No | [P1/P2/P3] |

## Overall Assessment

- Assessment Scope: [예: Unit Scope]
- Basis: [현재 판정의 근거]

### Verified Behaviors

- [실행 결과로 확인한 동작. 실행 전에는 `None`으로 기록한다.]

### Remaining Limitations

- [이번 판정에 포함되지 않은 경계와 남아 있는 위험]

### Conclusion

[검증 결과가 Objective와 Completion Criteria를 충족했는지 요약한다. 테스트를 실행하지 않았거나 필수 TC가 남아 있다면 그 이유로 아직 판정할 수 없음을 명시한다.]
