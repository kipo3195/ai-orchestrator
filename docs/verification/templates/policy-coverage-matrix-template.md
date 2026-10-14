# [Verification Title] Coverage Matrix

## Purpose

이 문서는 [Verification 대상]의 요구사항, 기대 결과, 부수 효과, Test Case와 자동화 테스트 사이의 연결을 빠르게 탐색하기 위한 요약 인덱스다.

Verification 상태와 TC별 실행 결과의 단일 원천은 [상위 Verification 문서](README.md)다. 상세한 Given/When/Then, 테스트 경계와 실행 기록은 연결된 TC 문서에서 관리한다. 이 Matrix는 그 내용을 대체하거나 독립적인 실행 결과를 갖지 않는다.

## Behavior Coverage

<!--
도메인에 맞는 조건 열을 사용한다. 각 행은 하나의 관찰 가능한 요구사항 또는 중요한 분기를 표현한다.
실행 결과 상태(Passed/Failed)는 이 표에 기록하지 않고 상위 Verification README에서 관리한다.
-->

| 실행 유형 또는 범위 | 입력/상태 조건 | 기대 결과 | 보장할 부수 효과 | TC | 자동화 테스트 |
|---|---|---|---|---|---|
| [실행 유형] | [조건] | [결정, 응답 또는 상태 전이] | [미호출, 상태 유지 등] | [TC-XXX](test-cases/TC-XXX-example.md) | [`testMethod`](테스트-코드-경로) |

## Resilience and Consistency Coverage

| 장애 또는 경계 | 기대 동작 | 보호 대상 | TC | 자동화 테스트 |
|---|---|---|---|---|
| [장애 조건] | [fallback, fail-closed, 상태 유지 등] | [보호하려는 자원 또는 불변식] | [TC-XXX](test-cases/TC-XXX-example.md) | [`testMethod`](테스트-코드-경로) |

## Verification Boundary Overview

| 검증 대상 | 현재 테스트 수준 | 실제 사용 컴포넌트 | 검증하지 않은 경계 |
|---|---|---|---|
| [대상] | [Unit / Integration / Workflow 등] | [실제 객체 및 경계] | [Mock 또는 현재 환경이 확인하지 못하는 부분] |

## Maintenance Rules

- Verification 상태와 실행 결과는 이 문서에 중복 기록하지 않고 상위 Verification README에서만 관리한다.
- 상세 Given/When/Then과 테스트 실행 기록은 각 TC 문서에서만 관리한다.
- 요구사항, 결정 결과, 중요한 부수 효과, TC 또는 자동화 테스트 매핑이 변경되면 이 Matrix를 함께 갱신한다.
- 구현되지 않은 미래 요구사항을 미리 행으로 만들지 않는다. 실제 범위가 확장될 때 추가한다.
