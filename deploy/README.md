# AI Orchestrator 배포 가이드

## Docker 컨테이너에서 호스트의 HTTPS 서비스 접근

Docker 컨테이너에서 같은 서버의 호스트에서 실행 중인 HTTPS 서비스에 접근할 때는
네트워크 경로와 TLS 호스트명 검증을 함께 고려해야 한다.

### 문제 상황

컨테이너에서 호스트로 접근하기 위해 다음과 같이 Docker 호스트 이름을 URL에 직접
사용할 수 있다.

```text
https://host.docker.internal:<port>
```

그러나 HTTPS 서비스의 인증서가 실제 서비스 도메인으로 발급되었다면 URL의
`host.docker.internal`과 인증서의 DNS 이름이 일치하지 않아 TLS 호스트명 검증이
실패한다.

HTTPS 포트에 `http://`로 요청하는 것도 올바르지 않다. TCP 연결은 성공할 수 있지만
클라이언트가 유효한 HTTP 응답을 읽지 못해 다음과 같은 오류가 발생할 수 있다.

```text
parsing HTTP/1.1 status line
```

### 권장 구성

애플리케이션 URL에는 TLS 인증서에 등록된 서비스 도메인을 사용하고, 컨테이너
내부에서만 그 도메인을 Docker 호스트 게이트웨이로 해석한다.

예를 들어 인증서의 서비스 도메인이 `service.example.com`이라면 Compose에 다음과
같이 설정한다.

```yaml
services:
  application:
    extra_hosts:
      # TLS 호스트명을 유지하면서 같은 서버의 호스트 서비스로 직접 연결한다.
      - "service.example.com:host-gateway"
```

애플리케이션의 서비스 URL에도 같은 도메인을 사용한다.

```properties
external-service.base-url=https://service.example.com:<port>
```

실제 프로퍼티 이름, 서비스 도메인과 포트는 연결 대상에 맞게 지정한다.

이 구성에서 이름 해석과 연결은 다음 순서로 이루어진다.

```text
https://service.example.com:<port>
        │
        ├─ TLS SNI, 인증서 검증, HTTP Host: service.example.com
        │
        └─ 컨테이너 /etc/hosts: service.example.com → host-gateway
                                                  │
                                                  └─ 같은 서버의 HTTPS 서비스
```

`extra_hosts`가 컨테이너의 외부 DNS 조회보다 먼저 적용되므로 요청은 서비스의 공인
주소를 경유하지 않는다. TLS 검증은 실제 접속 IP가 아니라 URL에 사용한 서비스
도메인을 기준으로 수행된다.

### 적용

`extra_hosts` 변경은 실행 중인 컨테이너에 자동 반영되지 않는다. Compose 설정을
변경한 후 해당 컨테이너를 재생성한다.

```bash
docker compose up -d --force-recreate <service-name>
```

### 확인

컨테이너의 호스트 매핑을 확인한다.

```bash
docker exec <container-name> cat /etc/hosts
```

출력에 다음 형태의 항목이 있어야 한다. 실제 IP는 Docker 네트워크 구성에 따라
달라질 수 있다.

```text
172.x.x.1 service.example.com
```

컨테이너에 진단 도구가 없다면 임시 컨테이너에서 호스트 게이트웨이 매핑과 HTTPS
연결을 확인할 수 있다.

```bash
docker run --rm \
  --add-host service.example.com:host-gateway \
  curlimages/curl:8.12.1 \
  -v https://service.example.com:<port>/<path>
```

정상이라면 TLS 인증서 검증이 성공하고 HTTP 상태 라인이 출력된다.

### 주의사항

- 운영 코드에서 TLS 인증서 검증을 비활성화하지 않는다.
- 인증서의 DNS 이름이 변경되면 URL과 `extra_hosts`의 호스트명도 함께 변경한다.
- 호스트 서비스는 Docker host-gateway에서 접근 가능한 인터페이스에 바인딩되어야 한다.
- `Connection refused` 또는 timeout은 서비스의 포트 바인딩과 방화벽을 확인한다.
- HTTP 상태 라인 파싱 오류는 HTTPS 포트에 HTTP로 요청했는지 먼저 확인한다.
- 인증서 호스트명 오류는 URL의 호스트명과 인증서 SAN이 일치하는지 확인한다.
