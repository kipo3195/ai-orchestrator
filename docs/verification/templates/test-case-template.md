# TC-XXX. [Test Case Title]

| 항목 | 값 |
|---|---|
| Verification | [상위 Verification 문서](../README.md) |
| Test Level | [Unit / Integration / Workflow / End-to-End / Manual] |

Required 여부, 시나리오 리뷰 상태, 자동화 상태, Assertion 리뷰 상태와 `Last Verification Execution` 기준 실행 결과는 [상위 Verification 문서](../README.md)에서 관리한다. 이 문서의 `Scenario Review`와 `Assertion Review`는 각 최신 리뷰 증적을, `Last Execution`은 해당 TC의 최근 단독 또는 전체 실행을 기록한다. 단독 실행만으로 상위 Verification의 상태를 변경하지 않는다.

## Verification Purpose

[이 TC가 확인하려는 하나의 핵심 동작을 설명한다. 구현 방식이 아니라 관찰 가능한 결과를 중심으로 작성한다.]

## Requirement Traceability

| 구분 | 참조 | 관련 요구사항 |
|---|---|---|
| Engineering / Decision | [문서](경로) | [이 TC가 검증하는 문장 또는 규칙] |
| Implementation | [구현 코드](경로) | [검증 대상 책임] |

## Risk Addressed

- [이 TC가 없을 때 발생할 수 있는 장애, 잘못된 허용/거절 또는 데이터 불일치]
- [실패가 사용자 또는 시스템에 미치는 영향]

## Test Level and Boundary

- 테스트 수준: [선택한 수준]
- 검증 대상: [직접 검증하는 클래스, 컴포넌트 또는 경계]
- 실제 사용 컴포넌트: [실제 객체로 사용하는 협력 대상]
- Test Double: [Mock, Stub, Fake를 사용하는 대상과 이유]
- 검증 제외: [이 TC에서 확인하지 않는 DB, 네트워크, Scheduler 등의 범위]

### Rationale

[이 테스트 수준으로 핵심 위험을 검증할 수 있는 이유와 더 높은 수준의 테스트가 필요한 부분을 설명한다.]

## Preconditions — Given

1. [테스트 시작 전에 성립해야 하는 상태]
2. [입력 데이터, 고정 시각, Mock 응답 또는 외부 환경]

## Test Data

| 항목 | 값 | 선택 이유 |
|---|---|---|
| [입력 또는 초기 상태] | [값] | [경계값 또는 대표값으로 선택한 이유] |

## Procedure — When

1. [검증 대상에 수행하는 동작]
2. [필요한 후속 동작]

## Expected Results — Then

<!-- 각 기대 결과는 실제 테스트 assertion 또는 관찰 가능한 증거와 연결할 수 있어야 한다. -->

1. [반환값, 상태 또는 예외에 대한 구체적인 기대 결과]
2. [협력 객체 호출 여부 또는 호출 횟수]
3. [변경되지 않아야 하는 상태 또는 부작용 부재]

## Scenario Review

<!--
상태: Draft | Ready for Review | In Review | Changes Requested | Approved | Stale
Verification Purpose, Requirement Traceability, Risk Addressed, 테스트 경계, Given/When/Then,
Test Data와 Limitations가 상위 Verification의 보장 범위를 충분히 표현하는지 검토한다.
Approved는 자동화 구현 또는 실행 통과를 의미하지 않는다.
체크리스트 판정: Pending | Accepted | Changes Requested | N/A
-->

| 항목 | 값 |
|---|---|
| Status | Draft |
| Reviewed At | - |
| Reviewer | - |
| Reviewed Commit | - |
| Review Evidence | - |
| Review Notes | - |

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
| [TestClass](테스트-코드-경로) | `testMethodName` | [Then 항목 번호 또는 핵심 assertion] |

## Assertion Review

<!--
상태: Draft | Ready for Review | In Review | Changes Requested | Approved | Stale
Automated Test Mapping의 실제 테스트 코드가 모든 Expected Results를 충분히 검증하는지 확인한다.
자동화 상태가 Automated이고 체크리스트가 모두 Accepted 또는 사유가 기록된 N/A일 때만 Approved로 전환한다.
체크리스트 판정: Pending | Accepted | Changes Requested | N/A
-->

| 항목 | 값 |
|---|---|
| Status | Draft |
| Reviewed At | - |
| Reviewer | - |
| Reviewed Commit | - |
| Review Evidence | - |
| Review Notes | - |

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

<!-- 이 TC에 필요한 테스트만 실행하도록 클래스 또는 메서드를 정확히 지정한다. -->

```sh
./gradlew test --tests 'fully.qualified.TestClass.testMethodName' --rerun-tasks
```

## Last Execution

<!-- 이 TC의 모든 매핑 테스트를 실행한 최신 결과를 기록한다. 실패 또는 차단된 실행도 기록하며, 실행 전에는 '-' 또는 'Not Run'을 유지한다. -->

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

- [테스트 데이터 삭제, 외부 리소스 정리 또는 실행 후 유지되어야 하는 상태]
- 해당 없음

## Limitations

- [이 TC가 보장하지 않는 범위]
- [동시성 반복 횟수, 시간 제한, Mock 사용 등 해석 시 주의할 점]
