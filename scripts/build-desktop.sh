#!/usr/bin/env bash
# build-desktop.sh — Build, version-bump, and deploy Mihon Desktop
#
# Version format: 0.STAGE.FEATURE.GIT_HASH
#   STAGE   = development stage (1-10, reflects Android feature parity)
#   FEATURE = feature batch within a stage
#   GIT_HASH = auto-injected by build.gradle.kts from git rev-parse --short=7 HEAD
#
# Usage:
#   ./scripts/build-desktop.sh           # build with current STAGE.FEATURE (hash auto-updates)
#   ./scripts/build-desktop.sh feature   # bump FEATURE (7.0 → 7.1), then build
#   ./scripts/build-desktop.sh stage     # bump STAGE, reset FEATURE (7.x → 8.0), then build

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_VERSION_FILE="$REPO_ROOT/app-desktop/src/main/kotlin/mihon/desktop/AppVersion.kt"
DEPLOY_DIR="/Applications/Mihon Desktop.app"
DIST_DIR="/private/tmp/mihon-dist/main/app/Mihon Desktop.app"

export JAVA_HOME="${JAVA_HOME:-/Users/altair/.jdks/jdk-21.0.10+7/Contents/Home}"

# ── Read current version ──────────────────────────────────────────────────────

STAGE=$(grep 'val STAGE' "$APP_VERSION_FILE" | grep -o '[0-9]\+')
FEATURE=$(grep 'val FEATURE' "$APP_VERSION_FILE" | grep -o '[0-9]\+')

# ── Bump version ──────────────────────────────────────────────────────────────

BUMP="${1:-hash}"
case "$BUMP" in
  stage)
    STAGE=$((STAGE + 1))
    FEATURE=0
    echo "Stage bump: → 0.$STAGE.$FEATURE"
    sed -i '' "s/val STAGE = [0-9]*/val STAGE = $STAGE/" "$APP_VERSION_FILE"
    sed -i '' "s/val FEATURE = [0-9]*/val FEATURE = $FEATURE/" "$APP_VERSION_FILE"
    ;;
  feature)
    FEATURE=$((FEATURE + 1))
    echo "Feature bump: → 0.$STAGE.$FEATURE"
    sed -i '' "s/val FEATURE = [0-9]*/val FEATURE = $FEATURE/" "$APP_VERSION_FILE"
    ;;
  hash)
    echo "Version: 0.$STAGE.$FEATURE (hash auto-updates at build time)"
    ;;
  *)
    echo "Unknown bump type: $BUMP. Use stage/feature/hash."
    exit 1
    ;;
esac

GIT_HASH=$(git -C "$REPO_ROOT" rev-parse --short=7 HEAD)
echo "Full version: 0.$STAGE.$FEATURE.$GIT_HASH"

# ── Build ─────────────────────────────────────────────────────────────────────

echo ""
echo "▶ Running tests..."
cd "$REPO_ROOT"
./gradlew :app-desktop:jvmTest

echo ""
echo "▶ Building distributable..."
./gradlew :app-desktop:createDistributable

# ── Deploy ────────────────────────────────────────────────────────────────────

echo ""
echo "▶ Deploying to $DEPLOY_DIR..."
rm -rf "$DEPLOY_DIR"
cp -R "$DIST_DIR" "$DEPLOY_DIR"

echo ""
echo "✓ Deployed Mihon Desktop 0.$STAGE.$FEATURE.$GIT_HASH"
echo "  → $DEPLOY_DIR"
