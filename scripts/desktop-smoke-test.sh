#!/bin/bash
# ============================================================================
# Desktop Smoke Test Runner
# ============================================================================
# Usage: ./scripts/desktop-smoke-test.sh [--report]
#
# Options:
#   --report   Generate HTML test report
# ============================================================================

set -uo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

GENERATE_REPORT=false

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
  --report)
    GENERATE_REPORT=true
    shift
    ;;
  --help | -h)
    echo "Desktop Smoke Test Runner"
    echo ""
    echo "Usage: $0 [options]"
    echo ""
    echo "Options:"
    echo "  --report, -r   Generate HTML test report"
    echo "  --help, -h    Show this help message"
    echo ""
    exit 0
    ;;
  *)
    echo "Unknown option: $1"
    exit 1
    ;;
  esac
done

cd "$(dirname "$0")/.."

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Desktop Smoke Test Runner${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Run smoke tests
echo -e "${YELLOW}► Running smoke tests...${NC}"

./gradlew :app-desktop:jvmTest --tests "*Smoke*" --rerun-tasks

if [ $? -eq 0 ]; then
  echo ""
  echo -e "${GREEN}========================================${NC}"
  echo -e "${GREEN}  ✓ All smoke tests passed!${NC}"
  echo -e "${GREEN}========================================${NC}"
else
  echo ""
  echo -e "${RED}========================================${NC}"
  echo -e "${RED}  ✗ Some tests failed!${NC}"
  echo -e "${RED}========================================${NC}"
  exit 1
fi

# Generate report (if requested)
if [ "$GENERATE_REPORT" = true ]; then
  echo ""
  echo -e "${YELLOW}► Generating test report...${NC}"
  REPORT_DIR="app-desktop/build/reports/tests/jvmTest"
  if [ -d "$REPORT_DIR" ]; then
    echo -e "${GREEN}✓ Report generated at: ${REPORT_DIR}${NC}"
    if command -v open &>/dev/null; then
      open "$REPORT_DIR/index.html" 2>/dev/null || true
    fi
  else
    echo -e "${YELLOW}⚠ Report directory not found${NC}"
  fi
fi

echo ""
echo -e "${BLUE}Done!${NC}"
