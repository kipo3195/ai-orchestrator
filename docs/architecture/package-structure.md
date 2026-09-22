# Package Structure

AI Orchestrator는 기능 단위(feature-first)로 패키지를 구성하고,
각 기능 내부는 `application / domain / infrastructure` 계층으로 나눈다.

## application

Use Case를 실행하고 전체 흐름을 조정한다.

* Domain 객체 및 정책 호출
* 외부 시스템 접근을 위한 interface 사용
* Transaction / workflow orchestration

비즈니스 규칙 자체는 가능한 Domain에 둔다.

### port

Application 또는 Domain이 외부 시스템이나 기술 구현에 직접 의존하지 않도록 하기 위한 추상화 경계이다.

Port는 내부 로직이 필요로 하는 기능을 interface 형태의 계약으로 정의하며, 실제 구현은 Infrastructure에서 담당한다.

예:

* `MessageContextPort` — 메시지 조회 방식 추상화
* `ConversationStartAiPort` — Omni AI 호출 방식 추상화
* `ConversationStartScheduler` — 지연 실행 방식 추상화
* `ConversationSuggestionSender` — 추천 결과 전달 방식 추상화
* `ConversationStartPolicyProvider` — 정책 조회 방식 추상화

Port의 이름에 반드시 `Port`를 붙일 필요는 없다.
`Repository`, `Provider`, `Sender`, `Scheduler`처럼 역할이 명확한 경우 해당 이름을 사용할 수 있다.

```text
Application
    ↓
   Port
    ↑
Infrastructure Adapter
```

예를 들어 Application에서는 다음과 같은 계약만 사용한다.

```java
public interface ConversationSuggestionSender {
    void send(String userId, String roomId, ConversationStartResult result);
}
```

실제 NATS 기반 전송 구현은 Infrastructure에 위치한다.

```java
public class NatsConversationSuggestionSender
        implements ConversationSuggestionSender {
    ...
}
```

따라서 Application은 실제 전달 방식이 NATS, REST, WebSocket인지 알 필요가 없다.

## domain

핵심 비즈니스 규칙과 상태를 표현한다.

* Entity / Value Object
* Policy
* Domain Model
* Repository interface

Spring, HTTP, DB, Redis, NATS 등 기술 구현에 의존하지 않는다.

Domain은 DB 테이블이나 외부 API 규격을 그대로 표현하는 계층이 아니라, 해당 기능에서 의미 있는 비즈니스 개념과 규칙을 표현한다.

예:

```text
ConversationStart
ConversationStartStatus
ConversationStartPolicy
ConversationStartResult
MessageContext
```

외부 API Response DTO나 DB Entity 등 기술 또는 저장 구조에 종속되는 모델은 Infrastructure에 둔다.

## infrastructure

외부 시스템과의 실제 연결을 담당한다.

즉 Application에서 정의한 Port를 Redis / DB / REST / NATS / Spring 등의 기술로 실제 구현하는 Adapter 계층이다.

또한 외부 시스템의 요청/응답 규격이나 저장소 구조에 종속되는 DTO, Entity 등의 모델도 Infrastructure에 둔다.

### DTO

DTO(Data Transfer Object)는 외부 시스템과 데이터를 주고받기 위한 전송 규격을 표현한다.

예:

* REST API Request / Response
* Omni AI Server Request / Response
* 다른 서비스의 API Response
* NATS Event Payload
* 외부 시스템의 JSON 구조

DTO는 외부 API나 메시징 규격에 영향을 받기 때문에 Domain Model과 분리한다.

예를 들어 Omni AI Server가 다음과 같은 요청 형식을 요구한다면:

```java
public record ConversationStartAiRequest(
        String userId,
        String roomId,
        List<MessageDto> messages
) {
}
```

해당 객체는 Conversation Start의 Domain Model이 아니라 외부 API 전송 규격이므로 다음과 같이 Infrastructure에 위치시킨다.

```text
infrastructure/
└── client/
    ├── OmniAiClient.java
    └── dto/
        ├── ConversationStartAiRequest.java
        └── ConversationStartAiResponse.java
```

Infrastructure Adapter는 Domain Model과 외부 DTO 사이의 변환도 담당한다.

```text
Domain Model
    ↓
Infrastructure Adapter
    ↓ 변환
External Request DTO
    ↓
REST / NATS / DB
```

반대로 외부 응답을 받을 때도 다음과 같이 변환한다.

```text
External Response DTO
    ↓
Infrastructure Adapter
    ↓ 변환
Domain Model
```

예:

```text
ConversationStartContext
        ↓
ConversationStartAiPort
        ↓
OmniAiClient
        ↓
ConversationStartAiRequest
        ↓
Omni AI Server
```

따라서 외부 API 필드명이나 JSON 규격이 변경되더라도 가능한 한 Infrastructure Adapter와 DTO 영역에서 변경을 흡수하고, Application과 Domain에는 영향을 최소화한다.

DB Entity 역시 같은 원칙을 적용한다.

```text
infrastructure/
└── repository/
    ├── entity/
    │   └── ConversationStartEntity.java
    └── RedisConversationStartRepository.java
```

DB 컬럼 구조나 Redis 저장 형식을 표현하는 객체는 Infrastructure에 두고, 필요하면 Domain Model로 변환하여 사용한다.

### web

REST API 등 외부 요청의 진입점.

Controller는 요청을 해석하고 Application Use Case를 호출하는 역할을 담당하며, 비즈니스 규칙은 직접 구현하지 않는다.

### client

다른 서비스 또는 Omni AI Server와의 통신을 담당한다.

예:

* `MessageContextClient`
* `OmniAiClient`

Application Port의 실제 REST/gRPC 등의 구현체가 위치한다.

### repository

DB / Redis 등 저장소 접근을 담당한다.

Domain 또는 Application에서 정의한 Repository 계약의 실제 persistence 구현을 둔다.

Repository interface 자체는 특정 저장 기술에 의존하지 않으며, 저장하거나 조회하려는 도메인 개념을 기준으로 정의한다.

다만, Repository는 기술적으로는 DB / Redis 같은 외부 영속성에 접근하기 위한 경계이지만, 단순한 저장소 접근보다 **도메인 객체의 상태를 저장하고 다시 복원하는 역할**에 가깝다.

따라서 특정 Domain 객체의 영속성 계약을 표현하는 Repository interface는 Domain에 정의할 수 있으며, 실제 DB / Redis 구현체는 Infrastructure에 둔다.

예:

```java
public interface ConversationStartRepository {

    void save(ConversationStart conversationStart);

    Optional<ConversationStart> findById(String executionId);

    Optional<ConversationStart> findActive(
            String userId,
            String roomId
    );
}
```

`ConversationStartRepository`는 내부적으로 Redis를 사용하는지 DB를 사용하는지 알지 못하며, `ConversationStart`의 저장 및 조회라는 계약만 표현한다.

실제 저장 기술에 대한 구현은 Infrastructure에 둔다.

```text
domain/
└── ConversationStartRepository.java

infrastructure/
└── repository/
    └── RedisConversationStartRepository.java
```

예:

```java
public class RedisConversationStartRepository
        implements ConversationStartRepository {
    ...
}
```

Infrastructure Repository는 다음과 같은 책임을 가진다.

* Redis / DB 등의 실제 저장소 접근
* 저장 Key 또는 Table 구조 관리
* Domain Model과 Persistence Model 간 변환
* 직렬화 / 역직렬화
* TTL 등 저장 기술에 종속적인 처리

예를 들어 Redis에 저장하기 위한 별도의 데이터 구조가 필요하다면 다음과 같이 둘 수 있다.

```text
infrastructure/
└── repository/
    ├── RedisConversationStartRepository.java
    └── entity/
        └── ConversationStartEntity.java
```

흐름은 다음과 같다.

```text
ConversationStartService
        ↓
ConversationStartRepository
        ↑
RedisConversationStartRepository
        ↓
Redis
```

Repository도 넓은 의미에서는 외부 저장소와의 경계를 추상화하는 Port의 한 종류이다.

다만 `ConversationStartRepository`처럼 Domain 객체의 영속성 자체를 표현하는 계약은 Domain에 둘 수 있으며, 실제 Redis / DB 구현만 Infrastructure에 위치시킨다.

또한 Repository의 역할은 실행 상태와 같이 실제 영속성이 필요한 데이터를 관리하는 것이며, 외부 서비스에서 데이터를 조회하는 역할과는 구분한다.

예:

```text
ConversationStartRepository
→ Conversation Start 실행 상태 저장 / 조회

ConversationStartPolicyProvider
→ 정책 정보 조회

MessageContextPort
→ 다른 서비스에서 메시지 Context 조회
```

각 구현이 모두 Redis 또는 REST를 사용할 수 있더라도, 저장하려는 데이터와 역할이 다르면 별도의 계약으로 분리한다.

### scheduler

시간 기반 또는 지연 실행을 담당한다.

예를 들어 Conversation Start에서 채팅방 진입 후 일정 시간이 지난 뒤 정책을 재검증하고 AI 실행 여부를 판단하도록 예약할 수 있다.

Scheduler는 실행 시점 관리만 담당하며, 실제 비즈니스 판단은 Application 또는 Domain에서 수행한다.

### messaging

NATS, JetStream 등 메시징 시스템과의 연결을 담당한다.

Event Publish / Subscribe 또는 사용자에게 결과를 전달하기 위한 Adapter 구현을 위치시킨다.

## Port / Adapter 원칙

외부 기술과 직접 연결되는 기능은 가능하면 Port를 통해 분리한다.

```text
ConversationStartService
        │
        ├── MessageContextPort
        │       ↑
        │   MessageContextClient
        │
        ├── ConversationStartAiPort
        │       ↑
        │   OmniAiClient
        │
        ├── ConversationStartScheduler
        │       ↑
        │   SpringConversationStartScheduler
        │
        └── ConversationSuggestionSender
                ↑
            NatsConversationSuggestionSender
```

Port는 "무엇을 해야 하는가"를 정의하고,
Infrastructure Adapter는 "어떻게 수행하는가"를 구현한다.

단, 모든 클래스를 interface로 추상화하지는 않는다.

외부 기술 또는 실행 방식과의 경계를 분리할 필요가 있는 경우 Port를 사용하고, 단순한 비즈니스 계산이나 하나의 구현만 필요한 Domain 로직은 구체 클래스로 시작할 수 있다.

## Shared Capability

여러 Feature에서 공통으로 사용하는 비즈니스 기능은 `common`에 모으지 않고 의미 있는 독립 패키지로 분리한다.

예:

* `execution`
* `context`
* `toolrelay`

예를 들어 Conversation Start에서만 사용하던 AI 실행 계약이 이후 Summary, Emergency Message 등에서도 동일하게 사용된다면 다음과 같이 공통 Capability로 승격할 수 있다.

```text
execution/
├── application/
│   └── port/
│       └── OmniAiPort.java
├── domain/
│   ├── AiTask.java
│   └── AiExecutionResult.java
└── infrastructure/
    └── client/
        └── OmniAiClient.java
```

단순 기술 공통 요소만 `shared`에 둔다.

예:

```text
shared/
├── config/
├── exception/
├── security/
└── util/
```

초기부터 모든 요소를 공통화하기보다는 하나의 Feature 내부에서 시작하고, 둘 이상의 Feature에서 실제로 동일한 개념과 계약이 반복되는 것이 확인되면 Shared Capability로 승격한다.
