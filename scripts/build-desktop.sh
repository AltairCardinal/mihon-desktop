#!/usr/bin/env bash
# Unified Mihon Desktop build entrypoint.
#
# Usage:
#   ./scripts/build-desktop.sh             # bump BUILD, then build unpackaged app
#   ./scripts/build-desktop.sh feature     # bump FEATURE, reset BUILD, then build
#   ./scripts/build-desktop.sh stage       # bump STAGE, reset FEATURE/BUILD, then build
#   ./scripts/build-desktop.sh msi         # bump BUILD, build MSI, then rebuild unpackaged app
#   ./scripts/build-desktop.sh evidence    # build a committed version allocation and seal provenance
#   ./scripts/build-desktop.sh test-only   # run tests only where supported
#   ./scripts/build-desktop.sh full-tests  # run full tests only where supported

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_VERSION_FILE="$REPO_ROOT/app-desktop/src/main/kotlin/mihon/desktop/AppVersion.kt"
HOST_OS="${MIHON_HOST_OS:-$(uname -s)}"
MODE="${1:-hash}"

read_version_constant() {
  local name="$1"
  grep "const val $name" "$APP_VERSION_FILE" | grep -o '[0-9]\+'
}

replace_version_constant() {
  local name="$1"
  local value="$2"
  if [[ "$HOST_OS" == "Darwin" ]]; then
    sed -i '' "s/const val $name = [0-9][0-9]*/const val $name = $value/" "$APP_VERSION_FILE"
  else
    sed -i "s/const val $name = [0-9][0-9]*/const val $name = $value/" "$APP_VERSION_FILE"
  fi
}

print_usage_and_exit() {
  echo "Unknown mode: $MODE"
  echo "Use: hash, feature, stage, msi, evidence, test-only, or full-tests."
  exit 1
}

STAGE="$(read_version_constant STAGE)"
FEATURE="$(read_version_constant FEATURE)"
BUILD="$(read_version_constant BUILD)"

case "$MODE" in
  stage)
    STAGE=$((STAGE + 1))
    FEATURE=0
    BUILD=1
    echo "Stage build: 0.$STAGE.$FEATURE.$BUILD"
    replace_version_constant STAGE "$STAGE"
    replace_version_constant FEATURE "$FEATURE"
    replace_version_constant BUILD "$BUILD"
    ;;
  feature)
    FEATURE=$((FEATURE + 1))
    BUILD=1
    echo "Feature build: 0.$STAGE.$FEATURE.$BUILD"
    replace_version_constant FEATURE "$FEATURE"
    replace_version_constant BUILD "$BUILD"
    ;;
  hash|msi)
    BUILD=$((BUILD + 1))
    echo "Build bump: 0.$STAGE.$FEATURE.$BUILD"
    replace_version_constant BUILD "$BUILD"
    ;;
  evidence)
    echo "Evidence build: 0.$STAGE.$FEATURE.$BUILD (committed version allocation required)"
    ;;
  test-only|full-tests)
    echo "Test version: 0.$STAGE.$FEATURE.$BUILD (version unchanged)"
    ;;
  *)
    print_usage_and_exit
    ;;
esac

GIT_HASH="$(git -C "$REPO_ROOT" rev-parse --short=7 HEAD)"
FULL_VERSION="0.$STAGE.$FEATURE.$BUILD.$GIT_HASH"
echo "Full version: $FULL_VERSION"

run_macos() {
  local DEPLOY_DIR="/Applications/Mihon Desktop.app"
  local DIST_DIR="/private/tmp/mihon-dist/main/app/Mihon Desktop.app"
  local PROVENANCE_SOURCE=""

  cleanup_macos_provenance_source() {
    if [[ -n "${PROVENANCE_SOURCE:-}" ]]; then
      rm -f -- "$PROVENANCE_SOURCE"
    fi
  }
  trap cleanup_macos_provenance_source EXIT

  export JAVA_HOME="${JAVA_HOME:-/Users/altair/.jdks/jdk-21.0.10+7/Contents/Home}"

  if [[ "$MODE" == "msi" ]]; then
    echo "MSI mode is only supported on Windows."
    exit 1
  fi

  cd "$REPO_ROOT"
  if [[ "$MODE" == "evidence" ]]; then
    PROVENANCE_SOURCE="$(mktemp "${TMPDIR:-/tmp}/mihon-task151-source.XXXXXX")"
    python3 scripts/task15-build-provenance.py source \
      --repo "$REPO_ROOT" --require-version-allocation --output "$PROVENANCE_SOURCE"
  fi
  echo ""
  echo "Running desktop JVM tests..."
  if [[ "$MODE" == "full-tests" ]]; then
    ./gradlew :app-desktop:jvmTest -PincludeIntegrationTests=true
  else
    ./gradlew :app-desktop:jvmTest
  fi

  if [[ "$MODE" == "test-only" || "$MODE" == "full-tests" ]]; then
    echo ""
    echo "macOS validation completed without packaging app bundle."
    return
  fi

  echo ""
  echo "Building macOS distributable..."
  ./gradlew :app-desktop:createDistributable

  echo ""
  echo "Deploying to $DEPLOY_DIR..."
  rm -rf "$DEPLOY_DIR"
  cp -R "$DIST_DIR" "$DEPLOY_DIR"

  if [[ "$MODE" == "evidence" ]]; then
    local ARTIFACT="$DEPLOY_DIR"
    python3 scripts/task15-build-provenance.py seal \
      --repo "$REPO_ROOT" --require-version-allocation \
      --source "$PROVENANCE_SOURCE" --artifact "$ARTIFACT" \
      --output "$ARTIFACT.task151-provenance.json"
    rm -f "$PROVENANCE_SOURCE"
    PROVENANCE_SOURCE=""
  fi

  echo ""
  echo "Deployed Mihon Desktop $FULL_VERSION"
  echo "  $DEPLOY_DIR"
}

run_windows() {
  cd "$REPO_ROOT"

  local WINDOWS_PS_SCRIPT="scripts/build-windows.ps1"
  local POWERSHELL_BIN=""
  if [[ -n "${MIHON_POWERSHELL_BIN:-}" ]]; then
    POWERSHELL_BIN="$MIHON_POWERSHELL_BIN"
  elif command -v pwsh >/dev/null 2>&1; then
    POWERSHELL_BIN="pwsh"
  elif command -v powershell.exe >/dev/null 2>&1; then
    POWERSHELL_BIN="powershell.exe"
  elif command -v powershell >/dev/null 2>&1; then
    POWERSHELL_BIN="powershell"
  else
    echo "PowerShell not found. Install PowerShell or run scripts/build-windows.ps1 directly from PowerShell."
    exit 1
  fi

  local ps_args=(-NoProfile -ExecutionPolicy Bypass -File "$WINDOWS_PS_SCRIPT")
  case "$MODE" in
    test-only)
      ps_args+=(-TestOnly)
      ;;
    full-tests)
      ps_args+=(-TestOnly -FullTests)
      ;;
    msi)
      ps_args+=(-PackageMsi -VersionAllocated -ExpectedVersion "$FULL_VERSION")
      ;;
    evidence)
      ps_args+=(-VersionAllocated -EvidenceProvenance -ExpectedVersion "$FULL_VERSION")
      ;;
    hash|feature|stage)
      ps_args+=(-VersionAllocated -ExpectedVersion "$FULL_VERSION")
      ;;
    *)
      print_usage_and_exit
      ;;
  esac

  echo ""
  echo "Dispatching to Windows PowerShell build script..."
  "$POWERSHELL_BIN" "${ps_args[@]}"
}

case "$HOST_OS" in
  Darwin)
    run_macos
    ;;
  MINGW*|MSYS*|CYGWIN*)
    run_windows
    ;;
  Linux*)
    if command -v powershell.exe >/dev/null 2>&1; then
      run_windows
    else
      echo "Linux desktop packaging is not configured for this repository."
      echo "Supported platforms: macOS via this script, Windows via scripts/build-windows.ps1 dispatch."
      exit 1
    fi
    ;;
  *)
    echo "Unsupported platform: $HOST_OS"
    exit 1
    ;;
esac
