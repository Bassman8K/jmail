# syntax=docker/dockerfile:1.7

# ---- build stage: compile the Compose Multiplatform app to WebAssembly ------
FROM eclipse-temurin:17-jdk AS build
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

HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
  CMD wget -qO- http://localhost/ >/dev/null || exit 1
