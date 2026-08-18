# syntax=docker/dockerfile:1.7

# ---- build stage -----------------------------------------------------------
# The Gradle cache is mounted rather than copied so repeat builds do not re-download
# the dependency graph.
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /src

RUN apk add --no-cache bash

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties LICENSE ./
COPY gradle gradle
COPY buildSrc buildSrc
COPY backend backend

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon -x test \
      -Pjmail.android.enabled=false -Pjmail.ios.enabled=false \
      :backend:bootJar

# ---- runtime stage ---------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

RUN apk add --no-cache wget curl tzdata && \
    addgroup -S jmail && adduser -S jmail -G jmail

COPY --from=build --chown=jmail:jmail /src/backend/build/libs/jmail-backend.jar app.jar

USER jmail
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
