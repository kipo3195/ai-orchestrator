# AI Orchestrator

AI Orchestrator는 UCWare 메신저 서비스와 Omni AI 사이에서 AI 기능의 실행을 제어하고, 실행 결과를 사용자에게 전달하는 Spring Boot 기반 백엔드 서비스입니다.

메신저 서비스에서 발생한 이벤트를 기반으로 AI 기능의 실행 가능 여부를 판단하고, 기능별 처리 상태와 실행 결과를 관리합니다.

실제 AI Workflow, Agent 및 LLM 처리는 별도 서비스인 `omni-ai-server`에서 수행하며, AI Orchestrator는 실행 정책, 요청 처리, 상태 관리 및 결과 전달을 담당합니다.

## 1. Architecture

```text
                  UCWare Services
                     (WS / NS)
                         |
                         v
              +---------------------+
              |   AI Orchestrator   |
              |---------------------|
              | Policy Evaluation   |
              | Feature Lifecycle   |
              | Execution Control   |
              | Result Routing      |
              +---------------------+
                  |      |      |
                  v      v      v
               MariaDB Redis  Omni AI
                                 |
                                 v
                         AI Workflow / LLM
```

**Responsibilities**

- AI 기능별 실행 정책 평가
- 메신저 이벤트 기반 AI 기능 실행 제어
- 기능별 요청 및 처리 상태 관리
- Omni AI 실행 요청 및 결과 처리
- 실시간 사용자 연결을 통한 결과 전달

## 2. Core Features

### AI Execution Policy

Global, Feature, User 단위의 정책을 평가하여 AI 기능의 실행 가능 여부를 결정합니다.

- Global / Feature Policy의 불변 Snapshot 관리 및 주기적 갱신
- User Policy Redis Cache 및 DB Fallback
- 유효한 정책을 확보할 수 없는 경우 실행 차단

Snapshot 갱신에 실패하면 마지막 정상 Snapshot을 유지합니다. 최초 Snapshot을 확보하지 못한 경우에는 실행을 차단합니다.

### Returned Message Topics

사용자가 부재 상태에서 온라인으로 복귀했을 때, 미확인 쪽지의 선택 정보를 바탕으로 Omni AI에 분석을 요청하고 결과를 전달합니다.

- 부재 구간 검증 및 미확인 쪽지 조회
- 선택한 쪽지의 식별자와 제목 저장, 사용자 확인에 따른 AI 실행
- Topic, 회신 필요 여부 및 Action 항목 분석 결과 처리
- DB 기반 요청 상태 관리 및 중복 lifecycle 생성 방지
- Callback / 상태 조회를 통한 실행 결과 반영 및 결과 전달 재시도

현재 Orchestrator는 쪽지 본문을 조회하거나 저장하지 않습니다. 실행 상태 조회와 결과 전달 복구를 제공하지만, 미전송된 AI 요청의 자동 재전송은 지원하지 않습니다.

### Conversation Start

대화방 입장 이벤트를 기반으로 AI 대화 시작 제안을 처리합니다.

- 대화방 입장 이벤트 처리 및 지연 실행 예약
- 세션 교체와 취소 처리, 실행 직전 정책 평가
- Omni AI TRENDING 요청 및 결과 전달

현재 실행 상태와 예약은 단일 프로세스의 메모리에서 관리하며, 재시작 복구와 인스턴스 간 중복 방지는 지원하지 않습니다.

### Result Routing

AI 처리 결과를 사용자와 연결된 실시간 메신저 서비스 인스턴스로 전달합니다.

- Redis 기반 사용자 연결 정보 조회
- 사용자 세션 및 연결 유형에 맞는 대상 인스턴스 결정
- HTTP REST를 통한 결과 이벤트 전달

## 3. Tech Stack

| Category | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.5.x |
| Build | Gradle Wrapper |
| Database | MariaDB / H2 |
| Cache / Connection Lookup | Redis |
| Persistence | Spring JDBC / JdbcClient |
| AI Integration | Omni AI (HTTP API) |
| Deployment | Docker / Docker Compose |

## 4. Getting Started

### Requirements

- JDK 17
- MariaDB 또는 사전 준비한 H2 DB, Redis (애플리케이션 실행 시)
- Omni AI 및 메신저 연동 서비스 (기능 통합 확인 시)
- Docker 및 Docker Compose (컨테이너 배포 시)

### Build

```bash
./gradlew clean build
```

### Test

```bash
./gradlew test
```

자동화 테스트는 H2와 로컬 HTTP 서버를 사용하므로 실제 외부 서비스 연결 없이 실행할 수 있습니다. HTML 테스트 결과는 `build/reports/tests/test/index.html`에서 확인합니다.

### Local Run

실행 전 DB 스키마, Redis 및 외부 서비스 연결 정보를 준비합니다. **local / prod 모두 DB 스키마 자동 초기화가 비활성화되어 있습니다** (`spring.sql.init.mode=never`). 사용할 DB에 맞는 [MariaDB 스키마](src/main/resources/db/mariadb/schema.sql) 또는 [H2 스키마](src/main/resources/db/h2/schema.sql)를 별도 DB 관리 절차로 먼저 적용해야 합니다.

기본 local 설정은 H2 메모리 DB입니다. 지속적으로 사용할 로컬 환경은 스키마를 준비한 H2 파일 DB 또는 MariaDB의 연결 정보를 지정합니다.

로컬 Routing 설정은 예제 파일을 복사하고 대상 인스턴스 주소를 수정합니다. 기존 설정 파일이 있다면 필요한 값만 반영합니다.

```bash
cp src/main/resources/config/result-router-local.example.properties \
   src/main/resources/config/result-router-local.properties
```

설정을 마친 후 local Profile로 실행합니다.

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

## 5. Configuration & Deployment

실행 환경별 설정은 Spring Profile과 환경변수로 관리합니다.

| File | Description |
|---|---|
| [application.properties](src/main/resources/application.properties) | 공통 설정 및 기능별 기본값 |
| [application-local.properties](src/main/resources/application-local.properties) | 로컬 개발 환경 |
| [application-prod.properties](src/main/resources/application-prod.properties) | 운영 환경 |
| [Routing 설정 예제](src/main/resources/config/result-router-local.example.properties) | 로컬 실시간 서비스 인스턴스 주소 설정 |
| [deploy/](deploy/) | Dockerfile 및 Compose 배포 파일 |

주요 환경변수는 다음과 같습니다. 실제 주소와 인증 정보는 환경변수 또는 저장소 밖 설정 파일로 주입합니다.

| Environment Variable | Description |
|---|---|
| `SPRING_PROFILES_ACTIVE` | 실행 Profile (`local` / `prod`, 기본값 `local`) |
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | DB 연결 정보 |
| `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT`, `SPRING_DATA_REDIS_PASSWORD` | Redis 연결 정보 |
| `OMNI_AI_BASE_URL` | Omni AI 기본 주소 |
| `OMNI_AI_RETURNED_MESSAGE_BASE_URL` | Returned Message의 Omni AI 주소를 별도 지정할 때 사용 |
| `RETURNED_MESSAGE_MESSAGE_QUERY_BASE_URL` | WS 메시지 조회 서비스 주소 |
| `WS_API_KEY`, `WS_API_KEY_HEADER` | WS 조회 및 결과 전달 인증 (기본 Header: `X-Service-Key`) |
| `AI_API_KEY`, `AI_API_KEY_HEADER` | Omni AI 호출 인증 (기본 Header: `X-API-Key`) |

API 키가 비어 있으면 해당 인증 Header를 생략합니다. 대상 서비스가 인증을 요구하는 경우 실행 전에 값을 설정해야 합니다. 위 인증 설정은 외부 서비스 호출에 적용됩니다.

Docker 배포 시 `./gradlew bootJar`로 생성한 실행 JAR을 `deploy/app.jar`로 복사하여 이미지를 빌드합니다. 배포 전 Compose의 Profile, DB / Redis 연결 정보, 외부 서비스 설정 및 Healthcheck 실행 도구를 확인합니다.

운영 환경의 DB 스키마 변경은 애플리케이션 시작과 분리된 별도 배포 절차로 관리합니다.

## 6. Documentation

상세 기능 명세와 주요 기술적 의사결정은 `docs/` 디렉터리에서 관리합니다.

| Document | Description |
|---|---|
| [AI Execution Policy](docs/features/policy.md) | 정책 평가 흐름, Snapshot 및 User Cache 동작 |
| [Returned Message Topics](docs/features/returned-message.md) | 복귀 요청, 사용자 확인, 실행 결과 및 복구 흐름, API 계약 |
| [Conversation Start](docs/features/conversation-start.md) | 실행 예약, 취소, 결과 전달 및 구현 범위 |
| [ADR 001: Policy Persistence Access](docs/decisions/001-policy-persistence-access.md) | 정책 저장소 접근 방식 |
| [ADR 002: User Policy Cache-aside and Fallback Isolation](docs/decisions/002-user-policy-cache-aside-and-fallback-isolation.md) | 사용자 정책 캐시 및 DB Fallback 격리 |
| [ADR 003: Returned Message Lifecycle Persistence](docs/decisions/003-returned-message-lifecycle-persistence.md) | Returned Message lifecycle 저장 및 상태 전이 경쟁 제어 |

기능별 상세 구현과 검증 범위는 [애플리케이션 코드](src/main/java/com/ucware/ai_orchestrator/) 및 [테스트 코드](src/test/java/com/ucware/ai_orchestrator/)를 참고합니다.
