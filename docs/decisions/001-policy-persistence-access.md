# 001. Policy Persistence Access

Status: Accepted

## Context

AI Orchestrator는 Global / Feature Policy 전체와 User Policy 단건을 관계형 데이터베이스에서 읽어야 한다.

Policy 데이터베이스는 정책의 Source of Truth이지만, AI Orchestrator는 정책 변경을 소유하지 않는다. 따라서 이 서비스에 필요한 persistence 기능은 복잡한 aggregate 저장이나 entity lifecycle 관리가 아니라 다음과 같은 읽기 전용 조회다.

- Global Policy 조회
- Feature Policy 전체 조회
- tenant와 user 식별자를 사용한 User Policy 단건 조회

현재 프로젝트에는 JDBC 또는 JPA 관련 의존성이 없다. Spring에서 사용할 데이터 접근 기술과 timeout 적용 경계를 먼저 정해야 한다.

## Decision Drivers

- 읽기 전용 조회에 적합해야 한다.
- 실행되는 SQL과 조회 범위가 명확해야 한다.
- DB query timeout을 적용할 수 있어야 한다.
- Application과 Domain이 Spring DB API에 직접 의존하지 않아야 한다.
- 현재 요구에 필요하지 않은 persistence abstraction을 도입하지 않아야 한다.
- 새로운 의존성을 최소화해야 한다.

## Considered Options

### Spring `JdbcClient`

- SQL과 row mapping이 명시적이다.
- 단건 조회와 작은 목록의 전체 조회에 적합하다.
- Spring JDBC의 transaction 및 query timeout 기능을 사용할 수 있다.
- persistence context와 entity lifecycle을 사용하지 않는다.

### Spring Data JDBC

- Repository abstraction과 aggregate mapping을 제공한다.
- 저장과 aggregate lifecycle이 필요한 경우 유용하다.
- 현재의 단순 읽기 전용 조회에는 필요한 수준보다 큰 abstraction이 될 수 있다.

### Spring Data JPA

- Entity mapping, repository, persistence context와 변경 감지를 제공한다.
- 복잡한 관계 탐색과 쓰기 모델에는 유용하다.
- 현재 Policy 조회에는 사용하지 않는 lifecycle과 ORM 동작이 추가된다.

## Decision

Policy persistence adapter는 Spring `JdbcClient`를 사용한다.

Spring JDBC 자동 설정과 connection pool을 사용하기 위해 `spring-boot-starter-jdbc`를 의존성으로 추가한다. 실제 JDBC driver는 Policy 데이터베이스 종류가 확정되었을 때 해당 runtime driver를 추가한다.

Application 또는 Domain에는 다음 역할의 기술 독립적인 interface를 둔다.

- `PolicySnapshotRepository`: Global / Feature Policy 전체 조회
- `UserAiPolicyRepository`: User Policy 단건 조회

`JdbcClient`에 의존하는 구현체와 persistence row mapping은 Infrastructure에 둔다. Repository interface에는 `JdbcClient`, SQL, `ResultSet` 등 Spring JDBC 또는 데이터베이스 기술 타입을 노출하지 않는다.

AI Orchestrator의 Policy repository는 읽기 기능만 제공한다. Admin Policy 변경과 User Policy 저장을 위한 메서드는 추가하지 않는다.

## Tenant Partitioning

현재 구현하는 Global Policy는 tenant별 전체 AI ON/OFF 값으로 관리한다. Persistence row와 in-memory snapshot은 모두 `tenantId`를 식별자로 사용한다.

Global Policy 테이블의 1차 schema는 다음 의미를 가진다.

```text
ai_global_policy
├── tenant_id (primary key)
└── enabled
```

특정 tenant의 row가 없으면 해당 tenant의 Global Policy는 OFF로 판단한다. 모든 tenant에 적용되는 암묵적인 ON 기본값은 두지 않는다.

Global / Feature Policy refresh는 전체 tenant 값을 한 번에 조회해 immutable snapshot으로 게시한다. 각 AI 요청은 자신의 `tenantId`로 snapshot을 조회하며 다른 tenant의 값으로 fallback하지 않는다.

향후 정책 평가는 다음 범위로 확장한다.

```text
Global
→ Tenant
→ Feature
→ User
```

현재 tenant별 Global ON/OFF 구현은 이 확장을 위한 첫 단계다. Feature와 User Policy를 추가하더라도 tenant 식별자는 모든 정책 조회 key에 포함한다.

## Timeout

DB query timeout은 Infrastructure의 JDBC 설정 또는 repository 실행 경계에 적용한다.

User Policy DB fallback의 허용 조건과 전체 요청 latency 동작은 [002. User Policy Cache-Aside and Fallback Isolation](002-user-policy-cache-aside-and-fallback-isolation.md)에서 정의한다. 이 ADR은 DB 조회에 timeout을 적용할 수 있는 기술적 기반만 결정한다.

Connection pool의 크기만으로 User Policy fallback 동시 실행을 제어하지 않는다. Connection pool은 데이터베이스 연결 자원을 제한하고, fallback concurrency limit은 Redis 장애 시 DB로 유입되는 요청 자체를 제한하는 서로 다른 보호 장치다.

## Consequences

### Positive

- SQL과 조회 비용을 코드에서 명확하게 확인할 수 있다.
- 읽기 전용 Policy 조회에 필요한 기능만 도입한다.
- ORM entity lifecycle과 persistence context에 의존하지 않는다.
- Domain과 Application을 Spring JDBC 및 DB schema로부터 분리할 수 있다.

### Negative

- SQL과 row mapping을 Infrastructure에서 직접 관리해야 한다.
- DB schema 변경이 repository 구현과 mapping에 직접 영향을 준다.
- Spring Data repository가 제공하는 자동 query 생성 기능을 사용하지 않는다.

## Non-goals

- Policy DB schema 결정
- 데이터베이스 제품 또는 JDBC driver 선택
- 데이터베이스 접속 정보 관리
- Policy 변경 transaction 구현
- User Policy Redis cache-aside 흐름
- Global / Feature Policy snapshot 갱신

## Related

- [002. User Policy Cache-Aside and Fallback Isolation](002-user-policy-cache-aside-and-fallback-isolation.md)
- [Policy Immutable Snapshot Refresh](../engineering/001-policy-immutable-snapshot-refresh.md)
