#!/usr/bin/env bash
#
# Generates and builds the iOS app.
#
#   ./scripts/build-ios.sh generate   create iosApp/JMail.xcodeproj from project.yml
#   ./scripts/build-ios.sh build      build for the simulator
#   ./scripts/build-ios.sh open       generate, then open it in Xcode
#
# The Xcode project is generated rather than committed: a .pbxproj is a machine-managed file
# that conflicts on every branch and cannot be reviewed. iosApp/project.yml says the same
# thing in a form a person can read.
#
set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

readonly PROJECT_DIR="iosApp"
readonly PROJECT="$PROJECT_DIR/JMail.xcodeproj"

die() { printf '\033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }
ok()  { printf '\033[32m✓\033[0m %s\n' "$*"; }
step(){ printf '\033[34m▶\033[0m %s\n' "$*"; }

require_macos() {
  [[ "$(uname)" == "Darwin" ]] || die "iOS builds need macOS."
  xcodebuild -version >/dev/null 2>&1 || die "Xcode is required. Install it from the App Store, then run: sudo xcode-select -s /Applications/Xcode.app"
}

require_xcodegen() {
  if ! command -v xcodegen >/dev/null 2>&1; then
    step "Installing XcodeGen"
    command -v brew >/dev/null 2>&1 || die "Homebrew is needed to install XcodeGen: https://brew.sh"
    brew install xcodegen
  fi
}

generate() {
  require_macos
  require_xcodegen
  step "Rendering the app icon into the asset catalog"
  ./gradlew :composeApp:generateIosAppIcon --quiet --console=plain
  step "Generating $PROJECT from project.yml"
  (cd "$PROJECT_DIR" && xcodegen generate)
  ok "Generated $PROJECT"
}

build() {
  require_macos
  [[ -d "$PROJECT" ]] || generate

  step "Building the shared Kotlin framework"
  ./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64 -Pjmail.ios.enabled=true

  step "Building the app for the simulator"
  xcodebuild \
    -project "$PROJECT" \
    -scheme JMail \
    -configuration Debug \
    -destination 'platform=iOS Simulator,name=iPhone 16' \
    -derivedDataPath "$PROJECT_DIR/build" \
    build | tail -20

  ok "Built. Run it from Xcode, or install the .app from $PROJECT_DIR/build/Build/Products/"
}

case "${1:-build}" in
  generate) generate ;;
  build)    build ;;
  open)     generate && open "$PROJECT" ;;
  *)        die "Usage: $0 [generate|build|open]" ;;
esac
