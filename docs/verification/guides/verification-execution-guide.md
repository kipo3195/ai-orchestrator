# Verification 실행 기록 가이드

테스트를 실제로 실행한 뒤 TC 단독 결과는 `Last Execution`에 기록하고, Verification 전체 실행은 evidence에 기록해 `Last Verification Execution`에 연결하는 방법을 설명한다.

## 기록 단위

- TC `Last Execution`: 해당 TC에 매핑된 모든 테스트의 최신 실행 결과다. 단독 실행은 `TC Only`, 상위 Verification 실행에 포함된 경우에는 `Verification`으로 기록한다.
- `Last Verification Execution`: 해당 Verification의 모든 Required TC를 동일한 소스와 환경에서 함께 실행한 evidence를 연결한다. Verification의 `Test Summary`와 상태는 이 evidence의 결과를 기준으로 판정한다.

여러 TC의 독립적인 실행 결과를 조합해 `Last Verification Execution`으로 기록하지 않는다.

## 1. 실행 전 정보 확인

```sh
git rev-parse --short HEAD
git status --short
java -version
rg '^distributionUrl=' gradle/wrapper/gradle-wrapper.properties
```

- `git status --short` 출력이 없으면 `Clean`, 있으면 `Dirty`다.
- 최종 `Verified` 판정은 가능한 한 `Clean` 상태에서 수행한다.
- 공통 환경과 같으면 `Environment`에 `Verification default`라고 기록하고, 다른 경우에만 차이를 명시한다.

## 2. TC 단독 실행

TC 문서의 `Execution Command`를 저장소 루트에서 실행한다. `--rerun-tasks`를 사용해 이전 Gradle 실행 결과를 재사용하지 않는다.

콘솔의 `BUILD SUCCESSFUL`은 Gradle 실행 성공을 의미한다. `actionable tasks`는 테스트 수가 아니므로 테스트 수와 결과는 리포트에서 확인한다.

## 3. 테스트 결과 확인

다음 HTML 리포트에서 전체 결과와 테스트 메서드를 확인한다.

```text
build/reports/tests/test/index.html
```

JUnit XML은 `build/test-results/test/TEST-*.xml`에 생성된다. 터미널에서는 다음 명령으로 확인한다.

```sh
rg -n '<testsuite |<testcase ' build/test-results/test/TEST-*.xml
```

| 속성 | 의미 |
|---|---|
| `tests` | 실행된 테스트 수 |
| `failures` | assertion 실패 수 |
| `errors` | 테스트 실행 오류 수 |
| `skipped` | 스킵된 테스트 수 |

여러 XML이 생성됐다면 값을 합산한다.

```text
Passed = tests - failures - errors - skipped
Failed = failures + errors
Skipped = skipped
```

`Automated Test Mapping`의 모든 테스트가 실행되고 `failures`, `errors`, `skipped`가 모두 0일 때만 `Passed`로 기록한다.

## 4. TC Last Execution 작성

TC 단독 실행 결과는 해당 TC 문서에 기록한다.

```markdown
## Last Execution

| 항목 | 값 |
|---|---|
| Executed At | 2026-10-13 16:11 KST |
| Commit | `9980610` |
| Working Tree | Dirty |
| Environment | Verification default |
| Command | 위 TC의 Execution Command |
| Execution Scope | TC Only |
| Parent Execution / Evidence | `build/reports/tests/test/index.html` |
| Result | Passed |
| Passed / Failed / Skipped | 3 / 0 / 0 |
```

`Executed At`에는 시간대를 명시한다. 테스트 실패는 `Failed`, 환경 문제로 완료하지 못한 실행은 `Blocked`, 실행 전에는 `Not Run`으로 기록한다.

## 5. Verification 전체 실행

Verification README의 `Verification Tests` 명령을 실행한다. 이 명령에는 해당 Verification의 모든 Required TC가 포함되어야 한다.

실행 결과에 모든 Required TC의 매핑 테스트가 포함됐는지 확인한 뒤 `evidence/YYYY-MM-DD-<commit>.md`에 실행 요약을 기록한다.

```markdown
## [Verification Title] Verification Execution

| 항목 | 값 |
|---|---|
| Executed At | 2026-10-13 17:00 KST |
| Commit | `abc1234` |
| Working Tree | Clean |
| Environment | Verification default |
| Command | Verification Tests 명령 |
| Required TC Coverage | TC-001, TC-002, TC-003, TC-004 |
| Result | Passed |
| Passed / Failed / Skipped | 12 / 0 / 0 |
```

상세 집계와 생성된 리포트 경로를 evidence에 추가한 뒤 Verification README의 최신 실행 링크를 갱신한다.

```markdown
## Last Verification Execution

- Execution Summary: [YYYY-MM-DD verification execution](evidence/YYYY-MM-DD-<commit>.md)
```

같은 실행에 포함된 각 TC의 `Last Execution`도 `Execution Scope: Verification`으로 갱신하고 상위 `Last Verification Execution`을 참조한다. Verification의 `Test Summary`와 상태는 연결된 evidence의 전체 실행 결과를 기준으로 갱신한다.

전체 실행이 통과했더라도 모든 Required TC의 시나리오 리뷰와 Assertion 리뷰 상태가 모두 `Approved`가 아니면 Verification을 `Verified`로 변경하지 않는다.

## 6. Evidence 관리

Verification 전체 실행은 다음 경로에 요약하고 `Last Verification Execution`에서 연결한다.

```text
evidence/YYYY-MM-DD-<commit>.md
```

로컬 리포트는 다음 실행이나 `clean`에서 덮어써질 수 있으므로 evidence에 실행 환경, 명령, Required TC 범위, 결과와 상세 집계를 기록한다. 상세 실패 로그나 CI 실행 링크는 필요한 경우 추가로 연결한다.
