# 프론트(React) + 백엔드(Spring Boot)를 하나의 이미지로 빌드한다.
# 프론트 빌드 결과(dist)를 Spring 의 static 리소스로 넣어 같은 서버가 화면과 API 를 함께 서빙한다.

# 1) 프론트 빌드
FROM node:22-alpine AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# 2) 백엔드 빌드
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# 의존성 레이어를 먼저 캐시한다(소스만 바뀌면 재다운로드하지 않음)
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src src
COPY --from=web /web/dist src/main/resources/static
# 프론트는 위 web 스테이지에서 이미 빌드했으므로 Gradle 의 프론트 빌드(Node 필요)는 건너뛴다
RUN ./gradlew --no-daemon bootJar -x test -PskipFrontend

# 3) 실행
FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home appuser
WORKDIR /app
COPY --from=build /app/build/libs/app.jar app.jar
USER appuser
ENV TZ=Asia/Seoul \
    SPRING_PROFILES_ACTIVE=prod
EXPOSE 5534
ENTRYPOINT ["java", "-jar", "app.jar"]
