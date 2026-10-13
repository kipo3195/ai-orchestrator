# Verification

구현의 보장과 한계를 재현 가능한 테스트 및 실행 증적으로 기록한다.

## 검증 문서 목록

| 문서 | 범위 |
|---|---|
| [001. Global AI Policy Snapshot Unit Verification](001-global-ai-policy-snapshot/README.md) | TC-001~004 단위 테스트 가이드와 후속 통합 테스트 경계 |

## 디렉터리 구성

각 검증 대상은 다음과 같은 구조로 관리한다.

```text
verification/
├── README.md
└── 001-global-ai-policy-snapshot/
    ├── README.md
    ├── test-cases/
    │   ├── TC-001.md
    │   ├── TC-002.md
    │   ├── TC-003.md
    │   └── TC-004.md
    └── evidence/
        └── YYYY-MM-DD-<commit>.md
```

## 문서별 역할

| 구분 | 역할 |
|---|---|
| 검증별 `README.md` | 검증 대상, 전략과 범위, TC 목록, `Last Verification Execution` 및 현재 검증 상태의 단일 원천 |
| `test-cases/` | TC별 검증 목적, 위험, Given(어떤 조건)/When(어떤 동작 발생)/Then(어떤 결과), 자동화 테스트 매핑, 단독 실행 명령과 `Last Execution`을 기록 |
| `evidence/` | README만으로 충분하지 않을 때 실행 요약이나 로그를 선택적으로 보관 |

## 검증 원칙

- **재현 가능성:** 테스트 조건, 실행 절차 및 명령을 기록한다.
- **추적 가능성:** TC와 자동화 테스트 코드, 실행 증적을 연결한다.
- **객관성:** 기대 결과와 실제 실행 결과를 구분한다.
- **검증 경계:** 테스트로 보장한 범위와 검증하지 않은 범위를 명시한다.

## 상태 관리

검증 상태, TC별 Required 여부와 자동화 상태는 각 검증의 `README.md`에서 관리한다. Test Summary의 TC별 실행 결과와 검증 상태는 모든 Required TC를 동일 조건에서 함께 실행한 `Last Verification Execution`을 기준으로 판정한다.

각 TC 문서의 `Last Execution`은 해당 TC가 가장 최근에 실행된 사실을 기록한다. 단독 실행 결과는 부분 실행 증적이며, 여러 TC의 독립적인 `Last Execution`을 조합해 `Last Verification Execution`으로 간주하거나 검증 상태를 `Verified`로 변경하지 않는다. `Last Verification Execution`에 포함된 TC의 `Last Execution`은 같은 전체 실행과 증적을 참조할 수 있다.

| 상태 | 의미 |
|---|---|
| `Draft` | 검증 범위와 TC를 작성하는 중이다. |
| `In Progress` | 테스트를 구현하거나 실행하는 중이다. |
| `Partial` | 일부 Required TC가 미구현·미실행·실패 상태이거나 실행 증적과 재현성을 포함한 완료 기준 일부를 충족하지 못했다. |
| `Verified` | 모든 완료 기준을 만족했다. |
| `Stale` | 마지막 검증 이후 관련 동작이 변경되어 재검증이 필요하다. |

### Stale 전환 규칙

`Verified` 이후 다음 변경이 검증 대상의 동작이나 검증 조건에 영향을 주면 상태를 `Stale`로 변경한다.

- Verification Basis에 연결된 요구사항, 설계 결정 또는 production code 변경
- Required TC의 삭제, 테스트 경계 변경 또는 핵심 assertion 변경
- 검증 결과에 영향을 주는 설정, schema 또는 외부 계약 변경

오탈자와 서식 수정처럼 검증 의미에 영향을 주지 않는 변경은 `Stale` 전환 대상이 아니다. `Stale`로 변경할 때는 기존 `Last Verified At`과 `Verified Commit`을 보존하고 `Stale Since`와 `Stale Reason`을 기록한다. 변경된 commit에서 모든 Required TC를 다시 실행해 완료 기준을 만족하면 `Verified`로 전환한다.

## 공통 완료 기준

Verification을 `Verified`로 판정하려면 다음 조건을 모두 만족해야 한다.

1. 모든 Required TC가 구현되어 있다.
2. 모든 Required TC가 동일한 commit에서 실패와 skip 없이 통과했다.
3. 실행 환경, working tree 상태, 명령과 결과가 기록되어 있다.
4. TC, 테스트 코드와 실행 증적이 연결되어 있다.
5. 검증하지 않은 경계와 알려진 위험이 명시되어 있다.
6. 최종 판정은 실제 검증 범위를 넘어서지 않는다.

검증별 `README.md`의 `Last Verification Execution`을 검증 완료 판정의 기본 실행 증적으로 사용한다. 이 기록은 해당 Verification의 모든 Required TC를 포함하는 하나의 실행이어야 하며, 개별 TC 실행 결과를 사후에 조합해서 만들지 않는다. 각 TC의 `Last Execution`은 단독 또는 전체 실행에서 해당 TC가 마지막으로 실행된 결과를 기록한다. CI 실행 링크나 별도 로그가 필요하면 `Evidence`에서 추가로 연결한다.

## 템플릿

- [Verification README 템플릿](templates/verification-readme-template.md)
- [Test Case 템플릿](templates/test-case-template.md)

## 실행 기록 가이드

- [Verification 실행 기록 가이드](guides/verification-execution-guide.md)

## 공통 실행 가이드

### 기본 환경

- JDK 17을 사용한다.
- 저장소 루트에서 실행한다.
- 이전 실행 결과의 재사용을 방지하기 위해 `--rerun-tasks`를 사용한다.

### 전체 회귀 테스트

```sh
./gradlew test --rerun-tasks
```

### 로컬 테스트 결과 위치

- HTML: `build/reports/tests/test/index.html`
- JUnit XML: `build/test-results/test/TEST-*.xml`
