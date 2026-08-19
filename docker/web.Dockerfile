# syntax=docker/dockerfile:1.7

# ---- build stage: compile the Compose Multiplatform app to WebAssembly ------
# `--platform=$BUILDPLATFORM` pins this stage to the machine doing the building. Its
# output is architecture-independent, so a multi-arch build compiles it once natively
# instead of running Gradle under QEMU emulation for every target.
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk AS build
WORKDIR /src

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties LICENSE ./
COPY gradle gradle
COPY buildSrc buildSrc
COPY shared shared
COPY composeApp composeApp

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon \
      -Pjmail.android.enabled=false -Pjmail.ios.enabled=false \
      :composeApp:wasmJsBrowserDistribution

# ---- runtime stage ---------------------------------------------------------
FROM nginx:1.27-alpine AS runtime

COPY --from=build /src/composeApp/build/dist/wasmJs/productionExecutable /usr/share/nginx/html
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

# 127.0.0.1, not localhost: inside the container localhost resolves to ::1 first, and
# these servers listen on IPv4 only — so the check fails against a server that is
# serving perfectly. The web container sat "unhealthy" through 247 consecutive checks.
HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
