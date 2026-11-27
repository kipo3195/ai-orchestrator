# Conversation Start

## 현재 동작

`POST /api/v1/conversation-starts`는 `roomSessionId`, `roomKey`, 선택적 `chatType`, `routingRef`로 추천을 예약한다. RoutingRef는 tenantId/userId/targetRef와 선택적 connectionType을 포함한다. chatType은 open 또는 chat으로 정규화한다.

같은 활성 roomSessionId의 재요청은 기존 실행을 반환한다. 같은 사용자/방의 새 세션은 이전 실행을 취소하고 새 execution ID로 예약한다. `SpringConversationStartScheduler`가 기본 10초 후 Runnable을 실행한다.

예약 실행은 다음을 확인한다.

1. 실행이 여전히 scheduled이며 현재 사용자/방·room session의 활성 실행인가
2. Global → Feature → User Policy가 허용하는가
3. 정책 조회 중 취소나 세션 교체가 발생하지 않았는가
4. 위 조건이 유지되면 EXECUTING으로 바꾸고 Omni TRENDING을 호출한다.
5. 결과 도착 후에도 실행 상태·현재 세션을 확인하고 완료 결과를 REST router로 전달한다.

정책 Redis/DB 조회는 서비스 synchronized 구간 밖에서 수행한다. 실행 상태 확인과 전이는 짧은 동기화 구간에서 수행한다. 취소된 실행의 늦은 AI 결과는 완료 처리하지 않는다.

## API 응답과 취소

예약 성공은 201이며 `executionId`, `workflowType`, `status`, `requestedAt`, `scheduledFor`, `data`를 반환한다. data는 roomSessionId/roomKey/chatType이다. Location header가 존재하지만 해당 실행을 조회하는 GET endpoint는 현재 구현하지 않았다.

`DELETE /api/v1/conversation-starts/{roomSessionId}?userID=...&roomKey=...`는 현재 매핑된 실행만 취소한다. 성공하면 204, 활성 실행이 없거나 owner/session이 맞지 않으면 404다. 오류는 기존 controller handler의 Problem Details 계약을 따른다.

취소는 로컬 실행 상태와 예약을 취소한다. 이미 전송한 Omni workflow를 취소하는 command는 없다. 결과 전달이 실패하면 로그를 남기며 Returned Message처럼 durable delivery retry를 제공하지 않는다.

## 구현 범위와 제한

- `InMemoryConversationStartRepository`가 실제 저장소다. 
- repository, synchronized와 scheduler는 단일 프로세스 범위다. 재시작 복구와 여러 instance 간 deduplication을 보장하지 않는다.
- repository는 종료된 execution과 결과를 자동 삭제하지 않는다.
- 현재 key는 user/room 및 roomSessionId 기준이며 tenant가 포함되지 않는다. 
- `conversation-start.cooldown=5m`은 기존 설정을 보존했지만 실행 코드에서 사용하지 않는다.
- TRENDING만 호출하며 최근 대화 기반 추천, Presence 외부 재조회는 구현하지 않았다. 실행 Context의 recentMessages는 빈 목록이다.
- API 키는 공통 AI 설정을 사용한다. HTTP timeout은 connect 3s, request 5s다.

