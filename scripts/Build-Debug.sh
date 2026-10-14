#!/usr/bin/env bash
# =============================================================================
# FolkPatch Debug Build Script (WSL)
# =============================================================================
# Usage: bash scripts/Build-Debug.sh
#
# Mirrors Build-Debug.bat functionality for WSL environment.
# Builds: cargo clean (apd) → Gradle assembleDebug
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# ── Colors ───────────────────────────────────────────────────────────────────
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

step() { echo -e "${GREEN}[$1/4]${NC} $2"; }
die()  { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

cd "$PROJECT_DIR"

# Load Rust env
[[ -f "${HOME}/.cargo/env" ]] && source "${HOME}/.cargo/env"

# ── Step 1: Clean apd (Rust) ────────────────────────────────────────────────
step 1 "Cleaning apd directory..."
cd apd
cargo clean || die "cargo clean failed!"
cd ..

# ── Step 2: Build Debug APK ────────────────────────────────────────────────
step 2 "Building debug APK with Gradle..."
./gradlew assembleDebug || die "Gradle assembleDebug failed!"

echo ""
echo -e "${GREEN}✅ Debug build complete!${NC}"
echo "  Output: app/build/outputs/apk/debug/*.apk"
