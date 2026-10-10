# Global AI Policy Snapshot Verification

Status: Draft

## Objective

AtomicReference와 불변 snapshot의 초기 상태, 전체 교체, 동시 접근, AI 호출 직전의 정책 차단을 검증한다

## Verification Target

- [구현 원칙](../../engineering/001-policy-immutable-snapshot-refresh.md)
- [저장소 및 동시성 테스트](../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/memory/InMemoryGlobalAiPolicyStoreTests.java)
- [Refresh 테스트](../../../src/test/java/com/ucware/ai_orchestrator/policy/infrastructure/scheduling/PolicySnapshotRefresherTests.java)
- [정책 판단 테스트](../../../src/test/java/com/ucware/ai_orchestrator/policy/application/AiExecutionPolicyServiceTests.java)
- [AI 실행 차단 테스트](../../../src/test/java/com/ucware/ai_orchestrator/conversationstart/application/ConversationStartServiceTests.java)

## Test Summary

| ID | Scenario | 실행 상태 |
|---|---|---|
| TC-01 | Initial State | - |
| TC-02 | Policy Replacement / Immutability | - |
| TC-03 | Concurrent Access | - |
| TC-04 | AI Execution Block | - |

## TC-01: Initial State

- 조건: 새 InMemoryGlobalAiPolicyStore를 생성한다.
- 절차: 
- 기대:
- 테스트:

## TC-02: Policy Replacement / Immutability

- 조건: tenant old=ON인 A와 tenant new=ON이며 로딩 시각이 다른 B를 준비한다.
- 절차: 
- 기대:
- 테스트:

## TC-03: Concurrent Access

- 조건: 정책 a/b와 loadedAt이 서로 다른 완전한 snapshot A/B를 준비한다.
- 절차: 
- 기대:
- 테스트:

## TC-04: AI Execution Block

- 조건: ConversationStart 예약 후 실제 예약 callback 실행 전 정책을 OFF, 미초기화, tenant 누락으로 바꾼다. ON 사례는 기존 정상 완료 테스트를 사용한다.
- 절차: 
- 기대:
- 테스트:
## Execution Guide

JDK 17을 준비한 뒤 저장소 루트에서 실행한다.

```sh
./gradlew test --tests '*policy.*' --tests '*ConversationStartServiceTests'
```

새 코드 변경 없이 결과를 다시 생성하려면 같은 명령에 --rerun-tasks를 추가한다. 특정 TC는 위 테스트 메서드를 --tests '패키지.클래스.메서드'로 지정해 실행할 수 있다.

- HTML: build/reports/tests/test/index.html
- JUnit XML: build/test-results/test/TEST-*.xml
- 전체 회귀 확인: ./gradlew test

## Integration Tests Needed

아래는 추가 구현을 권장하는 범위이며 이번 변경에서 작성하거나 실행한 통합 테스트는 아니다.

| 경계 | 검증 내용 | 단위 테스트로 부족한 이유 |
|---|---|---|
| JDBC → DB → refresh → store | 실제 테이블에서 ON/OFF/삭제를 읽어 전체 교체; 중복·빈 tenant 및 DB 장애 시 기존 snapshot 유지 | mock Map으로는 SQL, 컬럼 매핑, DB 제약 및 예외 전달을 확인할 수 없다. H2는 빠른 연결 검증용이며 운영 DB 호환성은 같은 DB 종류로 검증한다. |
| SQL NULL → 정책 상태 | enabled=NULL 입력의 처리와 게시 여부 | 현재 getBoolean은 wasNull 확인 없이 primitive boolean으로 매핑한다. null 정책은 OFF로 해석될 수 있어 Engineering의 필수 null 거절 규칙과 차이를 확인해야 한다. |
| Spring 설정·scheduler → refresh | 실제 bean 연결, refresh 주기 바인딩, 양수가 아닌 설정 거절, scheduled 진입점의 갱신 | 직접 메서드 호출은 @Scheduled 등록과 설정 바인딩을 검증하지 않는다. 별도 짧은 주기와 제한 시간 내 관측으로 확인한다. |
| 요청 → 예약 실행 → 정책 → AI adapter | DB에서 OFF로 변경하고 refresh 완료 후 예약 작업이 REJECTED인지, HTTP stub 요청이 0건인지; ON일 때 호출·결과 전달 | mock Runnable/AI port 테스트는 실제 scheduler, 라우팅 tenant 및 HTTP adapter의 연결을 확인하지 않는다. 실제 유료 AI 대신 HTTP stub을 사용한다. |

순수 snapshot 불변성·정책 분기는 단위 테스트가 적합하다. 통합 테스트는 위 연결 경계에 집중한다.

## Conclusion

테스트 실행 완료 후 결과와 증적 링크를 근거로 작성할 예정이다.
