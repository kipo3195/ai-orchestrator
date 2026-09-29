FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --chown=10001:10001 app.jar /app/app.jar

USER 10001:10001

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

# 이 Dockerfile은 Gradle이나 소스 코드를 사용하지 않고, 같은 디렉터리에 있는 app.jar만 이미지에 넣습니다.