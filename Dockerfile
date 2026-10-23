# 서버(mini)에는 Java 25 가 없어서(Java 21) 이미지 안에서 빌드한다: 서버에 도구를 깔지 않아도 배포된다.
# settings.gradle 의 includeBuild('../Doro') 때문에 Doro 의 SDK 소스가 같은 상대 경로(형제 디렉터리)에 있어야 한다.
# BuildKit 의 이름 있는 컨텍스트로 가져온다:  docker build --build-context doro=<Doro 체크아웃> .  (compose 는 additional_contexts)
FROM gradle:9.5.1-jdk25 AS build
WORKDIR /work
# Doro 는 SDK 만 필요하다. Gradle 이 include 한 나머지 서브프로젝트 디렉터리는 비어 있어도 있어야 한다(Gradle 9: 없는 프로젝트 디렉터리는 오류).
COPY --from=doro settings.gradle /work/Doro/settings.gradle
COPY --from=doro sdk/build.gradle /work/Doro/sdk/build.gradle
COPY --from=doro sdk/src /work/Doro/sdk/src
RUN mkdir -p /work/Doro/auth /work/Doro/guard /work/Doro/bff
WORKDIR /work/doro-notify
COPY settings.gradle build.gradle ./
COPY src src
RUN --mount=type=cache,target=/home/gradle/.gradle gradle bootJar --no-daemon -x test -q

FROM eclipse-temurin:25-jdk-alpine

WORKDIR /app

# Non-root 사용자 생성
RUN addgroup -S doro && adduser -S doro -G doro
USER doro:doro

COPY --from=build --chown=doro:doro /work/doro-notify/build/libs/doro-notify-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8089

HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8089/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
