# syntax=docker/dockerfile:1.7

# ---- build stage -----------------------------------------------------------
# The Gradle cache is mounted rather than copied so repeat builds do not re-download
# the dependency graph.
# Noble rather than Alpine: temurin's Alpine tags are published for linux/amd64 only, and
# this image is built for arm64 too so Apple Silicon does not run the backend under emulation.
#
# `--platform=$BUILDPLATFORM` pins this stage to the machine doing the building. Its
# output is architecture-independent, so a multi-arch build compiles it once natively
# instead of running Gradle under QEMU emulation for every target.
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk-noble AS build
WORKDIR /src

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties LICENSE ./
COPY gradle gradle
COPY buildSrc buildSrc
COPY backend backend

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon -x test \
      -Pjmail.android.enabled=false -Pjmail.ios.enabled=false \
      :backend:bootJar

# ---- runtime stage ---------------------------------------------------------
FROM eclipse-temurin:17-jre-noble AS runtime
WORKDIR /app

RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends wget curl tzdata \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system jmail \
 && useradd --system --gid jmail --no-create-home --shell /usr/sbin/nologin jmail

COPY --from=build --chown=jmail:jmail /src/backend/build/libs/jmail-backend.jar app.jar

USER jmail
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom"

# 127.0.0.1, not localhost: inside the container localhost resolves to ::1 first, and
# these servers listen on IPv4 only — so the check fails against a server that is
# serving perfectly. The web container sat "unhealthy" through 247 consecutive checks.
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
