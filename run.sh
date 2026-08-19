#!/usr/bin/env bash
#
# JMail — one command to run everything.
#
#   ./run.sh              start the whole stack and tell you where to click
#   ./run.sh docker       run everything in Docker, no Java needed
#   ./run.sh test         run every test and the coverage gate
#   ./run.sh desktop      launch the desktop app
#   ./run.sh web          serve the browser app
#   ./run.sh package      build installers for this machine's platform
#   ./run.sh status       what is running
#   ./run.sh logs         follow the backend log
#   ./run.sh restart      rebuild and restart the backend
#   ./run.sh down         stop everything
#   ./run.sh reset        stop everything and delete the database
#
set -Eeuo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

readonly COMPOSE_FILE="docker/compose.yml"
readonly BACKEND_LOG="build/backend.log"
readonly BACKEND_PID_FILE="build/backend.pid"
readonly WEB_PID_FILE="build/web.pid"

# ---------------------------------------------------------------------------
# Output helpers. Colour only when attached to a terminal, so piping to a file
# or a CI log stays readable.
# ---------------------------------------------------------------------------
if [[ -t 1 ]]; then
  BOLD=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[31m'; GREEN=$'\033[32m'
  YELLOW=$'\033[33m'; BLUE=$'\033[34m'; RESET=$'\033[0m'
else
  BOLD=''; DIM=''; RED=''; GREEN=''; YELLOW=''; BLUE=''; RESET=''
fi

step()  { printf '%s▶%s %s\n' "$BLUE" "$RESET" "$*"; }
ok()    { printf '%s✓%s %s\n' "$GREEN" "$RESET" "$*"; }
warn()  { printf '%s!%s %s\n' "$YELLOW" "$RESET" "$*"; }
fail()  { printf '%s✗%s %s\n' "$RED" "$RESET" "$*" >&2; }
die()   { fail "$*"; exit 1; }

on_error() {
  local line=$1
  fail "Failed at line $line."
  [[ -f "$BACKEND_LOG" ]] && printf '%sLast lines of %s:%s\n' "$DIM" "$BACKEND_LOG" "$RESET" && tail -20 "$BACKEND_LOG"
  exit 1
}
trap 'on_error $LINENO' ERR

# ---------------------------------------------------------------------------
# Prerequisites
# ---------------------------------------------------------------------------
require_prerequisites() {
  local missing=0

  if ! command -v docker >/dev/null 2>&1; then
    fail "Docker is not installed. Get it from https://docs.docker.com/get-docker/"
    missing=1
  elif ! docker info >/dev/null 2>&1; then
    fail "Docker is installed but not running. Start Docker Desktop and try again."
    missing=1
  fi

  if ! command -v java >/dev/null 2>&1; then
    fail "Java 17 or newer is required. Try: brew install openjdk@17"
    missing=1
  else
    local major
    major=$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
    if [[ "$major" -lt 17 ]]; then
      fail "Java 17 or newer is required (found $major)."
      missing=1
    fi
  fi

  [[ $missing -eq 0 ]] || die "Install the missing prerequisites above, then run ./run.sh again."
}

# This project lives under ~/Documents, which iCloud Drive syncs. When it resolves a
# conflict it leaves "Foo 2.kt" copies behind, and Gradle then compiles both — producing
# duplicate-declaration errors that have nothing to do with your changes. Sweeping them
# before a build keeps that failure mode out of the way.
remove_sync_duplicates() {
  local found
  found=$(find . -name "* [0-9].*" -path "*/build/*" 2>/dev/null | wc -l | tr -d ' ')
  if [[ "$found" -gt 0 ]]; then
    find . -name "* [0-9].*" -path "*/build/*" -delete 2>/dev/null || true
    warn "Removed $found file-sync duplicate(s) from build directories"
  fi
}

ensure_env_file() {
  if [[ ! -f .env ]]; then
    cp .env.example .env
    ok "Created .env from .env.example (demo sign-in is enabled; no credentials needed)"
  fi
}

# Exports the settings in .env to this shell, so the backend actually receives the OAuth
# credentials a user has filled in. Only KEY=VALUE lines are read and each value is assigned
# rather than evaluated, so nothing in the file can execute.
load_env_file() {
  [[ -f .env ]] || return 0

  local line key value
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" =~ ^[[:space:]]*$ ]] && continue
    [[ "$line" != *=* ]] && continue

    key="${line%%=*}"
    value="${line#*=}"
    key="${key//[[:space:]]/}"
    # Strip one layer of surrounding quotes, which people add out of habit.
    value="${value%\"}"; value="${value#\"}"
    value="${value%\'}"; value="${value#\'}"

    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    export "$key=$value"
  done < .env
}

# Reads a value from .env without sourcing it, so a stray line cannot execute.
env_value() {
  local key=$1 fallback=${2:-}
  local value
  value=$(grep -E "^${key}=" .env 2>/dev/null | tail -1 | cut -d= -f2- || true)
  printf '%s' "${value:-$fallback}"
}

stop_pid_file() {
  local file=$1 label=$2
  if [[ -f "$file" ]]; then
    local pid
    pid=$(cat "$file")
    if kill -0 "$pid" 2>/dev/null; then
      # bootRun spawns the application as a child, so the whole group goes.
      kill -- -"$(ps -o pgid= "$pid" 2>/dev/null | tr -d ' ')" 2>/dev/null || kill "$pid" 2>/dev/null || true
      ok "Stopped $label"
    fi
    rm -f "$file"
  fi
}

# ---------------------------------------------------------------------------
# Infrastructure
# ---------------------------------------------------------------------------
start_infrastructure() {
  step "Starting PostgreSQL"
  docker compose -f "$COMPOSE_FILE" --env-file .env up -d postgres >/dev/null 2>&1

  printf '  waiting for PostgreSQL'
  local waited=0
  until docker compose -f "$COMPOSE_FILE" ps --format '{{.Name}} {{.Status}}' 2>/dev/null |
        grep -q 'jmail-postgres.*healthy'; do
    printf '.'
    sleep 1
    waited=$((waited + 1))
    [[ $waited -lt 90 ]] || { printf '\n'; die "PostgreSQL did not become healthy within 90s. See: docker compose -f $COMPOSE_FILE logs postgres"; }
  done
  printf '\n'
  ok "PostgreSQL is ready on port $(env_value POSTGRES_PORT 5432)"
}

backend_is_up() {
  local port=$1
  curl -fsS -o /dev/null --max-time 2 "http://localhost:${port}/actuator/health" 2>/dev/null
}

# A backend left running while the code was rebuilt ends up with a classpath that no longer
# matches its class files, and fails at runtime with NoClassDefFoundError on whatever it
# happens to load next. Restarting it when the sources are newer keeps that from looking
# like an application bug.
backend_is_stale() {
  [[ -f "$BACKEND_PID_FILE" ]] || return 1

  local newest
  newest=$(find backend/src shared/src -type f -newer "$BACKEND_PID_FILE" 2>/dev/null | head -1)
  [[ -n "$newest" ]]
}

start_backend() {
  local port
  port=$(env_value BACKEND_PORT 8090)

  if backend_is_up "$port"; then
    if backend_is_stale; then
      warn "The backend is running older code than your checkout; restarting it"
      stop_pid_file "$BACKEND_PID_FILE" "the stale backend"
      pkill -f "com.jmail.backend.JMailApplicationKt" 2>/dev/null || true
      sleep 2
    else
      ok "Backend already running on port $port"
      return
    fi
  fi

  remove_sync_duplicates
  step "Building and starting the backend"
  mkdir -p build
  load_env_file

  # bootRun keeps the JVM in the foreground, so it is backgrounded here and its
  # pid recorded; `./run.sh down` stops it again.
  JMAIL_DEMO_ENABLED=true ./gradlew :backend:bootRun --quiet --console=plain \
    --args="--spring.profiles.active=local --server.port=${port}" \
    > "$BACKEND_LOG" 2>&1 &
  echo $! > "$BACKEND_PID_FILE"

  printf '  waiting for the backend'
  local waited=0
  until backend_is_up "$port"; do
    printf '.'
    sleep 2
    waited=$((waited + 2))

    if grep -q "APPLICATION FAILED TO START\|BUILD FAILED" "$BACKEND_LOG" 2>/dev/null; then
      printf '\n'
      tail -30 "$BACKEND_LOG"
      die "The backend failed to start. Full log: $BACKEND_LOG"
    fi
    [[ $waited -lt 180 ]] || { printf '\n'; die "The backend did not start within 3 minutes. Log: $BACKEND_LOG"; }
  done
  printf '\n'
  ok "Backend is ready on http://localhost:${port}"

  local configured=()
  [[ -n "${GOOGLE_CLIENT_ID:-}" ]] && configured+=("Google")
  [[ -n "${MICROSOFT_CLIENT_ID:-}" ]] && configured+=("Microsoft")
  [[ -n "${APPLE_CLIENT_ID:-}" ]] && configured+=("Apple")

  if [[ ${#configured[@]} -gt 0 ]]; then
    ok "OAuth sign-in enabled for: ${configured[*]}"
  fi
}

# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------
command_up() {
  require_prerequisites
  ensure_env_file
  start_infrastructure
  start_backend

  local backend_port adminer_port
  backend_port=$(env_value BACKEND_PORT 8090)
  adminer_port=$(env_value ADMINER_PORT 8081)

  cat <<BANNER

${BOLD}JMail is running.${RESET}

  ${BOLD}API${RESET}            http://localhost:${backend_port}
  ${BOLD}API docs${RESET}       http://localhost:${backend_port}/docs
  ${BOLD}Database UI${RESET}    http://localhost:${adminer_port}   ${DIM}(server: postgres, user: jmail)${RESET}

${BOLD}To try the app:${RESET}
  ${BOLD}./run.sh desktop${RESET}   the desktop app  ${DIM}(macOS, Windows, Linux)${RESET}
  ${BOLD}./run.sh web${RESET}       the browser app  ${DIM}(http://localhost:3000)${RESET}

Sign in with ${BOLD}"Explore the demo mailbox"${RESET} — no account or credentials needed.

  ${DIM}./run.sh test    run every test with coverage${RESET}
  ${DIM}./run.sh logs    follow the backend log${RESET}
  ${DIM}./run.sh down    stop everything${RESET}

BANNER
}

# Runs the whole stack in containers using the backend image published by CI, so a
# machine with Docker and nothing else can run JMail. `./run.sh` proper builds the backend
# from source, which is what you want while changing it; this is for trying it out.
command_docker() {
  command -v docker >/dev/null 2>&1 || die "Docker is required. Install Docker Desktop, then run this again."
  docker info >/dev/null 2>&1 || die "Docker is installed but not running. Start Docker Desktop, then run this again."
  ensure_env_file

  local backend_image="${JMAIL_BACKEND_IMAGE:-ghcr.io/bassman8k/jmail-backend:latest}"
  local web_image="${JMAIL_WEB_IMAGE:-ghcr.io/bassman8k/jmail-web:latest}"
  export JMAIL_BACKEND_IMAGE="$backend_image" JMAIL_WEB_IMAGE="$web_image"

  step "Pulling $backend_image"
  step "Pulling $web_image"
  if docker compose -f "$COMPOSE_FILE" --env-file .env --profile full pull backend web 2>&1 | tail -3; then
    step "Starting the stack"
    # --no-build is what makes Compose use the pulled images: a service declaring both
    # `image` and `build` is otherwise built and tagged locally, never pulled.
    docker compose -f "$COMPOSE_FILE" --env-file .env --profile full up -d --no-build
  else
    warn "Could not pull the published images; building from source instead."
    unset JMAIL_BACKEND_IMAGE JMAIL_WEB_IMAGE
    docker compose -f "$COMPOSE_FILE" --env-file .env --profile full up -d --build
  fi

  local backend_port web_port
  backend_port=$(env_value BACKEND_PORT 8090)
  web_port=$(env_value WEB_PORT 3000)

  step "Waiting for the API"
  local waited=0
  until curl -fsS "http://localhost:${backend_port}/actuator/health" >/dev/null 2>&1; do
    sleep 2; waited=$((waited + 2))
    [[ $waited -lt 120 ]] || die "The backend did not become healthy within 120s. See: docker compose -f $COMPOSE_FILE logs backend"
  done

  cat <<BANNER

${BOLD}JMail is running, entirely in Docker.${RESET}

  ${BOLD}Web app${RESET}        http://localhost:${web_port}
  ${BOLD}API${RESET}            http://localhost:${backend_port}
  ${BOLD}API docs${RESET}       http://localhost:${backend_port}/docs

Sign in with ${BOLD}"Explore the demo mailbox"${RESET} — no account or credentials needed.

  ${DIM}./run.sh down    stop everything${RESET}

BANNER
}

command_desktop() {
  require_prerequisites
  ensure_env_file
  start_infrastructure
  start_backend

  remove_sync_duplicates
  step "Launching the desktop app"
  JMAIL_API_URL="http://localhost:$(env_value BACKEND_PORT 8090)" \
    ./gradlew :composeApp:run --quiet --console=plain
}

command_web() {
  require_prerequisites
  ensure_env_file
  start_infrastructure
  start_backend

  local backend_port web_port
  backend_port=$(env_value BACKEND_PORT 8090)
  web_port=$(env_value WEB_PORT 3000)

  remove_sync_duplicates
  step "Building the browser app (first build downloads the WebAssembly toolchain)"
  ./gradlew :composeApp:wasmJsBrowserDistribution --quiet --console=plain

  local dist="composeApp/build/dist/wasmJs/productionExecutable"
  [[ -d "$dist" ]] || die "The web build produced no output at $dist"

  # The browser build talks to the backend on another origin in development; the
  # backend's CORS configuration already allows it.
  cat > "$dist/config.js" <<CONFIG
window.JMAIL_API_URL = "http://localhost:${backend_port}";
CONFIG
  if ! grep -q 'config.js' "$dist/index.html"; then
    # Loaded before the app bundle so the URL is set by the time Kotlin runs.
    sed -i.bak 's|<script type="application/javascript" src="jmail.js">|<script src="config.js"></script>\n    <script type="application/javascript" src="jmail.js">|' "$dist/index.html"
    rm -f "$dist/index.html.bak"
  fi

  mkdir -p build
  step "Serving the browser app"
  (cd "$dist" && python3 -m http.server "$web_port" >/dev/null 2>&1) &
  echo $! > "$WEB_PID_FILE"

  sleep 2
  ok "JMail is at ${BOLD}http://localhost:${web_port}${RESET}"
  printf '\n  Sign in with %s"Explore the demo mailbox"%s. Press Ctrl-C to stop.\n\n' "$BOLD" "$RESET"

  wait "$(cat "$WEB_PID_FILE")"
}

command_test() {
  require_prerequisites
  ensure_env_file
  start_infrastructure   # the integration tests use this PostgreSQL

  remove_sync_duplicates
  step "Running every test with coverage"
  ./gradlew verifyAll --console=plain

  cat <<REPORTS

${BOLD}Reports${RESET}
  Coverage   build/reports/kover/html/index.html
  Backend    backend/build/reports/tests/test/index.html
  Shared     shared/build/reports/tests/desktopTest/index.html
  UI         composeApp/build/reports/tests/desktopTest/index.html

REPORTS
}

command_package() {
  require_prerequisites
  remove_sync_duplicates

  # Installers are staged outside the project tree. macOS refuses to sign an app bundle
  # carrying `com.apple.FinderInfo`, and sync/backup agents re-attach it to files under the
  # project faster than jpackage can strip it — the failure surfaces as an opaque codesign
  # error. Building elsewhere and copying the finished artifacts back avoids the race.
  local staging
  staging="${TMPDIR:-/tmp}/jmail-dist-$$"
  rm -rf "$staging"

  step "Building installers for this platform"
  ./gradlew :composeApp:packageDistributionForCurrentOS :backend:bootJar \
    -Pjmail.dist.dir="$staging" --console=plain

  mkdir -p dist
  find "$staging" -type f \
    \( -name '*.dmg' -o -name '*.msi' -o -name '*.deb' -o -name '*.rpm' -o -name '*.exe' -o -name '*.pkg' \) \
    -exec cp {} dist/ \; 2>/dev/null || true
  cp backend/build/libs/jmail-backend.jar dist/ 2>/dev/null || true
  rm -rf "$staging"

  ok "Installers are in ./dist"
  ls -lh dist 2>/dev/null | tail -n +2 | awk '{printf "  %-40s %s\n", $9, $5}'
}

command_status() {
  printf '%sContainers%s\n' "$BOLD" "$RESET"
  docker compose -f "$COMPOSE_FILE" ps --format 'table {{.Name}}\t{{.Status}}' 2>/dev/null || echo "  none"

  printf '\n%sBackend%s\n' "$BOLD" "$RESET"
  local port
  port=$(env_value BACKEND_PORT 8090)
  if backend_is_up "$port"; then
    ok "http://localhost:${port} is healthy"
  else
    warn "not running"
  fi
}

command_logs() {
  [[ -f "$BACKEND_LOG" ]] || die "No backend log yet. Start it with ./run.sh"
  tail -f "$BACKEND_LOG"
}

command_down() {
  step "Stopping JMail"
  stop_pid_file "$BACKEND_PID_FILE" "the backend"
  stop_pid_file "$WEB_PID_FILE" "the web server"

  # bootRun's JVM can outlive the Gradle wrapper process that launched it.
  pkill -f "com.jmail.backend.JMailApplicationKt" 2>/dev/null || true

  docker compose -f "$COMPOSE_FILE" down >/dev/null 2>&1 || true
  ok "Containers stopped (the database volume is kept; use ./run.sh reset to delete it)"
}

command_reset() {
  command_down
  step "Deleting the database volume"
  docker compose -f "$COMPOSE_FILE" down -v >/dev/null 2>&1 || true
  ok "Everything removed. ./run.sh will set it all up again from scratch."
}

usage() {
  # The header comment block is the help text, so adding a command in one place keeps
  # `--help` correct. Read to the first non-comment line rather than a fixed range.
  sed -n '2,/^[^#]/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'
}

case "${1:-up}" in
  up|start|"")  command_up ;;
  docker)       command_docker ;;
  desktop|app)  command_desktop ;;
  web|browser)  command_web ;;
  test|check)   command_test ;;
  package|dist) command_package ;;
  status)       command_status ;;
  logs)         command_logs ;;
  restart)      command_down && command_up ;;
  down|stop)    command_down ;;
  reset|clean)  command_reset ;;
  -h|--help|help) usage ;;
  *) fail "Unknown command: $1"; echo; usage; exit 1 ;;
esac
