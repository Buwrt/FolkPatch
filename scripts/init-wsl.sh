#!/usr/bin/env bash
# =============================================================================
# FolkPatch WSL Quick Start - Clone + Setup + Build
# =============================================================================
# Usage:
#   bash <(curl -sL https://raw.githubusercontent.com/LyraVoid/FolkPatch/main/scripts/init-wsl.sh)
#
# Or download first:
#   curl -sLO https://raw.githubusercontent.com/LyraVoid/FolkPatch/main/scripts/init-wsl.sh
#   bash init-wsl.sh
# =============================================================================

set -euo pipefail

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }

# ── Config ────────────────────────────────────────────────────────────────────
REPO_URL="${FOLKPATCH_REPO:-https://github.com/LyraVoid/FolkPatch.git}"
TARGET_DIR="${FOLKPATCH_DIR:-$HOME/FolkPatch}"

echo ""
echo "=========================================="
echo -e "${GREEN}FolkPatch WSL Quick Start${NC}"
echo "=========================================="
echo ""

# ── Step 1: Clone ────────────────────────────────────────────────────────────
if [[ -d "${TARGET_DIR}" ]]; then
    warn "Directory ${TARGET_DIR} already exists, skipping clone."
else
    info "[1/3] Cloning FolkPatch to ${TARGET_DIR}..."
    git clone --recursive "${REPO_URL}" "${TARGET_DIR}"
    ok "Repository cloned."
fi

cd "${TARGET_DIR}"

# ── Step 2: Setup environment ───────────────────────────────────────────────
info "[2/3] Running setup-wsl.sh (installing JDK/SDK/NDK/Rust/CMake)..."
bash scripts/setup-wsl.sh

# ── Step 3: Verify build ────────────────────────────────────────────────────
info "[3/3] Verifying build environment..."
source "${HOME}/.cargo/env" 2>/dev/null || true

echo ""
echo "=========================================="
echo -e "${GREEN}All Done! 🎉${NC}"
echo "=========================================="
echo ""
echo "Project location : ${TARGET_DIR}"
echo "Build command    : bash scripts/Build-Debug.sh"
echo "VS Code          : code ${TARGET_DIR}"
echo ""
echo "Tips:"
echo "  - Use 'Ctrl+Shift+B' in VS Code for quick build"
echo "  - Rust builds (apd/fpd) benefit most from WSL native filesystem"
echo "  - First build will be slow (downloading dependencies), subsequent builds use ccache"
echo ""
