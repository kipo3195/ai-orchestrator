# AI Orchestrator 문서

이 디렉터리는 AI Orchestrator의 구조, 주요 기술 결정, 구현 원칙과 검증 근거를 기록한다.

최종 수정일: 2026-10-09

문서는 다음 흐름으로 읽을 수 있다.

```text
Architecture → Decisions → Engineering → Verification
전체 구조       선택 근거      구현 원칙       보장과 검증
```

## 문서 구성

### Architecture

서비스의 전체 구조와 코드 구성 원칙을 설명한다.

### Decisions

중요한 기술 선택의 배경, 검토한 대안, 최종 결정과 그에 따른 영향을 ADR 형태로 기록한다.

### Engineering

결정된 구조를 코드로 구현할 때 지켜야 할 구체적인 동작과 경계를 정리한다.

### Verification

설계와 구현이 의도한 보장을 실제로 충족하는지 확인한 근거를 기록한다.

## 문서 관리 규칙

- Decisions, Engineering, Verification 문서는 각 분류에서 독립적으로 증가하는 `{3자리 번호}-{kebab-case 설명}.md` 형식을 사용한다.
- 한 번 부여한 번호는 문서를 삭제하거나 폐기해도 재사용하거나 변경하지 않는다.
- 같은 번호를 가진 서로 다른 분류의 문서가 관련 문서임을 의미하지 않는다. 문서 간 관계는 `Related` 또는 `Verification Target` 링크로 명시한다.
- 문서를 추가·삭제하거나 상태를 변경하면 아래 문서 목록과 최종 수정일을 함께 갱신한다.
- 표의 상태는 각 문서에 명시된 상태를 기준으로 작성하며, 상태가 정의되지 않은 문서는 `-`로 표시한다.

## 문서 목록

### Architecture

| 문서 | 상태 | 설명 |
| --- | --- | --- |
| [Package Structure](architecture/package-structure.md) | - | 기능 단위 패키지와 `application / domain / infrastructure` 계층, Port/Adapter 및 공통 기능 분리 원칙 |

### Decisions

| 문서 | 상태 | 설명 |
| --- | --- | --- |
| [001. Policy Persistence Access](decisions/001-policy-persistence-access.md) | Accepted | 읽기 전용 Policy 저장소 접근에 Spring `JdbcClient`를 사용하는 결정 |
| [002. User Policy Cache-Aside and Fallback Isolation](decisions/002-user-policy-cache-aside-and-fallback-isolation.md) | Accepted | Redis cache-aside, DB fallback, fail-closed 및 동시 실행 제한 정책 |

### Engineering

| 문서 | 상태 | 설명 |
| --- | --- | --- |
| [001. Policy Immutable Snapshot Refresh](engineering/001-policy-immutable-snapshot-refresh.md) | Draft | Global/Feature Policy를 immutable snapshot으로 갱신하고 원자적으로 게시하는 방식 |

### Verification

| 문서 | 상태 | 설명 |
| --- | --- | --- |
| [001. Global AI Policy Snapshot](verification/001-global-ai-policy-snapshot/README.md) | Draft | 초기 상태, 불변 snapshot 교체, 동시 접근, AI 실행 차단 검증 및 증적 가이드 |
