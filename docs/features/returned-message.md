# Returned Message Topics

## 현재 동작

```text
복귀 요청 → 입력·정책·권한 평가 → DETECTED 저장
  → WS MSG_UNREAD 첫 페이지 조회 → 선택 key·제목 저장
  → AWAITING_CONFIRMATION
     NO → DECLINED
     만료 → EXPIRED
     YES → 정책·권한 재평가 → PROCESSING 저장 → 비동기 Omni start
       → callback 또는 status 조회 → 결과 저장·DELIVERY_PENDING → REST 전달
```

Client가 팝업을 자체 생성한다. Orchestrator는 별도 confirmation prompt를 전송하지 않는다. AI에는 선택한 source item key·제목·순서를 전달하며 메시지 본문을 조회하거나 저장하지 않는다.

## REST 계약

기본 경로는 `/api/v1/returned-messages`다. API version은 `api.version` 설정을 따른다.

| 요청 | 필드 |
|---|---|
| POST 기본 경로 | `requestId`, `triggerId`, `absencePeriodId`, `offlineSince`, `returnedAt`, `occurredAt`, `expiresAt`, `routingRef` |
| POST `/{requestId}/confirmations` | `absencePeriodId`, `confirmationId`, `messageSelectionRef`, `decision` YES/NO, `decidedAt`, `routingRef` |
| POST `/{requestId}/execution-results` | `eventId`, `tenantId`, `userId`, `executionId`, `taskType`, `contractVersion`, `status`, 선택적 `result`, `failureReason`; header `X-Tenant-Id` 필수 |

`routingRef`는 `tenantId`, `userId`, `targetRef`, 양수 `connectionType`을 포함한다. owner는 source-qualified `routingRef`에서 읽는다. legacy connectionType의 값별 적용 대상 판정은 Client Ingress 책임이며 Orchestrator는 양수 여부만 검증한다.

성공 응답은 `requestId`, `triggerId`, `outcome`, `reason`, `status`, `unreadCount`, `confirmationId`, `messageSelectionRef`, `confirmationExpiresAt`, `executionId`, `lifecycleEvent`를 포함한다. 해당하지 않는 값은 null일 수 있다. 복귀 요청은 200, YES 결과가 PROCESSING/DELIVERY_PENDING이면 202, 그 외 confirmation과 callback 성공은 200이다.

성공 DTO와 오류 schema는 분리한다. 기존 handler는 validation·충돌·거절·저장소 장애를 RFC 9457 Problem Details로 반환한다. 오류 `type`은 `about:blank`, title은 HTTP 표준 reason phrase다. 

callback은 task type `RETURNED_MESSAGE_TOPICS`, contract version `2`와 header/body tenant 일치를 검사한다. 발신자 서비스 인증을 추가한 것은 아니다. result handler는 저장된 execution ID와 상태 조건으로 반영한다. 다른 execution 결과와 늦은 terminal 결과는 현재 snapshot으로 응답할 수 있다. 모든 payload 충돌을 별도 오류로 거절하는 계약은 구현하지 않았다. `eventId`는 요청 필수값이나 별도 callback event 이력 테이블에 저장하지 않는다.

## 저장 임계점과 멱등성

기능 enabled, 시간 관계 `offlineSince < returnedAt <= expiresAt`, DB 시각상 trigger 유효성, 부재 기간, Global/Feature/User Policy와 Permission을 확인한 뒤 lifecycle을 생성한다. 기간·정책상 비대상 요청은 lifecycle을 생성하지 않는다.

`returned_message_request`의 primary key는 `(tenant_id, request_id)`이며 unique key는 `(tenant_id, trigger_id)`, `(tenant_id, user_id, absence_period_id)`다. insert 충돌은 기존 owner/correlation과 비교하여 정상 중복 또는 계약 충돌로 구분한다. 정상 중복은 기존 snapshot을 반환하고 최초 메시지 조회를 다시 시작하지 않는다.

경쟁하는 상태 변경은 현재 상태, 필요한 owner/correlation, DB 현재 시각을 SQL WHERE에 포함한다. update 1건만 승자로 처리하고 0건이면 상태를 다시 읽는다. 외부 HTTP 호출 중 DB transaction·row lock은 유지하지 않는다.

## 메시지 선택과 확인

현재 WS adapter는 `MSG_UNREAD`, `reqcount=30`, `reqpage=1`로 조회한다. `unreadCount`는 저장된 선택 항목 수이며 전체 미확인 쪽지 수가 아니다. adapter는 현재 `offlineSince`부터 조회하며, application이 계산한 `lookbackSince`는 실제 HTTP startDate에 반영되지 않는다. 

선택 metadata는 `returned_message_selection`, key·nullable subject·순서는 `returned_message_selection_item`에 저장한다. 동일 request의 유효 selection은 재사용한다. 유효기간은 조회와 confirmation에 사용되며 DB row 자동 삭제를 뜻하지 않는다.

메시지가 없으면 SKIPPED_NO_MESSAGES, 유효 선택이 있으면 AWAITING_CONFIRMATION, 조회 오류·잘못된 선택이면 FAILED가 된다. confirmation 만료는 DB 현재 시각 기준 `min(confirmation timeout, selection expiresAt)`으로 저장한다.

YES는 저장된 owner·confirmation·selection·client session·connectionType을 확인하고 정책/권한을 재평가한다. 저장 selection 항목 수와 lifecycle unreadCount의 일치도 확인한다. **YES에서 MSG_UNREAD를 다시 조회하지 않는다.** 조건부 PROCESSING 전이에 성공한 처리자만 execution pool에 등록한다. NO나 만료가 먼저 승리하면 AI를 호출하지 않는다.

## 실행 결과와 전달

start와 status는 동일한 tenant/execution ID를 사용한다. start의 400/422, 409는 명시적 실패로 처리하고 timeout·기타 불확실한 실패는 PROCESSING을 유지한다. callback과 polling은 같은 result handler를 사용한다.

v2 결과는 `topics`, 메시지별 `messageAssessments`, `generatedAt`로 구성된다. 결과가 성공하면 payload와 stable delivery ID를 먼저 저장한 다음 delivery pool에 등록한다. 저장 JSON의 기존 v1 형태는 명시적 legacy reader로 변환한다. 결과 source reference가 선택 집합에 포함되는지에 대한 전체 검증과 저장 contract version column은 아직 없다.

결과 전달은 Redis에서 현재 connection pointer와 owner record를 확인하고, pointer를 재확인하여 session/connectionType과 맞는 owner에 REST로 보낸다. Realtime 2xx를 전달 완료로 취급하며 Client 표시 완료를 뜻하지 않는다. 전달 실패 때문에 AI start를 재호출하지 않는다.

`delivery-max-retries`는 최초 시도 외 추가 횟수다. 기본 1은 최초 실패 후 한 번 더 시도한다. 일반 RuntimeException 경로는 pending만 유지하여 retry count를 소비하지 않을 수 있다. 최초 delivery worker와 Recovery가 겹칠 수 있으므로 외부 중복 억제 계약 없이 exactly-once를 보장하지 않는다.

Client 공개 상태는 내부 상태와 구분한다. DELIVERY_PENDING/COMPLETED는 TOPICS_READY로 매핑되며 공개 상태가 바뀌지 않는 내부 전이에서는 event ID·revision을 유지한다.

Client 결과 payload는 `topics`, `counts`, `generatedAt`을 포함한다. `counts`는 Orchestrator가 전달 대상 `topics[].messages[]`를 기준으로 계산한다. `total`은 전체 메시지 수, `urgent`는 priority가 URGENT인 메시지 수, `replyRequired`와 `actionRequired`는 각각 해당 값이 true인 메시지 수, `other`는 priority가 UNKNOWN인 메시지 수다. 각 항목은 독립적으로 집계하므로 중복될 수 있다.

## 만료와 복구

확인의 유효성은 sweeper 실행 여부와 관계없이 DB 만료 조건으로 결정한다. sweeper는 batch 크기 안에서 만료 상태를 materialize한다.

| 상태 | 현재 Recovery 동작 |
|---|---|
| DETECTED | trigger 만료 확인 후 메시지 선정 재개 |
| AWAITING_CONFIRMATION | claim 후 추가 작업 없이 대기. 만료는 sweeper 처리 |
| PROCESSING | Omni status 조회. NOT_FOUND이면 유지하며 자동 재전송하지 않음 |
| DELIVERY_PENDING | 저장된 결과로 전달만 재시도 |

claim은 owner token과 lease를 사용한다. queue 거절이나 process 종료로 미전송된 실행은 NOT_FOUND 상태로 남을 수 있다. backoff/jitter/deadline metadata, row별 손상 payload 격리, start/cancel 공통 계약의 완전한 구현은 없다. 현재 목록 역직렬화 중 한 row 오류가 batch를 중단할 수도 있다.

## 설정

| 설정 | 기본값 |
|---|---|
| min-away-duration / max-absence-duration / max-lookback | 1m / 7d / 24h. max-lookback은 현재 adapter 조회에 미반영 |
| confirmation-timeout / message-query.selection-ttl | 2m / 5m |
| expiration/recovery sweep interval | 각각 30s |
| expiration/recovery batch size | 각각 100 |
| recovery-claim-duration | 30s |
| execution/delivery pool size / queue capacity | 4 / 2 / 각각 100 |
| delivery-max-retries | 1 |

결정 배경은 [ADR 003](../decisions/003-returned-message-lifecycle-persistence.md)에 정리되어 있다.
