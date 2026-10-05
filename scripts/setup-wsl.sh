#!/usr/bin/env bash
# =============================================================================
# FolkPatch WSL Build Environment Setup Script
# =============================================================================
# Prerequisites: Ubuntu 24.04 (or compatible) on WSL2
# Usage: bash scripts/setup-wsl.sh
#
# This script installs everything needed to build FolkPatch from WSL:
#   - JDK 21
#   - Android SDK (cmdline-tools, build-tools, platform)
#   - Android NDK r29
#   - Rust + cargo-ndk
#   - CMake 3.28+
#   - ccache
# =============================================================================

set -euo pipefail

# ── Colors ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

info()    { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()      { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()     { echo -e "${RED}[ERROR]${NC} $*"; }

# ── Resolve project root ─────────────────────────────────────────────────────
_SCRIPT_LOCATION="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_PROJECT_CANDIDATE="$_SCRIPT_LOCATION"

while [[ "$_PROJECT_CANDIDATE" != "/" ]]; do
    if [[ -f "${_PROJECT_CANDIDATE}/build.gradle.kts" ]]; then
        PROJECT_DIR="$_PROJECT_CANDIDATE"
        break
    fi
    _PROJECT_CANDIDATE="$(dirname "$_PROJECT_CANDIDATE")"
done

if [[ -z "${PROJECT_DIR:-}" ]] || [[ ! -f "${PROJECT_DIR}/build.gradle.kts" ]]; then
    if [[ -f "$(pwd)/build.gradle.kts" ]]; then
        PROJECT_DIR="$(pwd)"
    else
        err "Cannot find build.gradle.kts."
        echo "  Script location: $_SCRIPT_LOCATION"
        echo "  Current dir   : $(pwd)"
        echo ""
        echo "  Run from project root:"
        echo "    cd ~/FolkPatch && bash scripts/setup-wsl.sh"
        exit 1
    fi
fi

unset _SCRIPT_LOCATION _PROJECT_CANDIDATE

# ── Paths ────────────────────────────────────────────────────────────────────
ANDROID_HOME="${HOME}/Android/Sdk"
NDK_VERSION="29.0.14206865"
BUILD_TOOLS_VERSION="36.1.0"
COMPILE_SDK_VERSION="36"

# ── Pre-checks ──────────────────────────────────────────────────────────────
info "FolkPatch WSL Environment Setup"
info "==============================="
info "Project dir : ${PROJECT_DIR}"
info "Android Home: ${ANDROID_HOME}"
echo ""

# ── Step 1: System packages ─────────────────────────────────────────────────
info "[1/7] Installing system dependencies..."
echo "  (apt-get update + install cmake/ninja/ccache/build-essential/...)"

sudo apt-get update
sudo apt-get install -y \
    wget unzip git curl \
    cmake ninja-build \
    ccache \
    build-essential \
    pkg-config \
    python3

ok "System packages installed"

# ── Step 2: JDK 21 ──────────────────────────────────────────────────────────
info "[2/7] Installing JDK 21..."

if ! java -version 2>&1 | grep -q "21\."; then
    echo "  Installing openjdk-21-jdk (this may take a minute)..."
    sudo apt-get install -y openjdk-21-jdk
fi

JAVA_HOME="$(dirname $(dirname $(readlink -f $(which java))))"
ok "JDK 21 at ${JAVA_HOME}"

# ── Step 3: Android SDK Command-line Tools ──────────────────────────────────
info "[3/7] Installing Android SDK command-line tools..."

mkdir -p "${ANDROID_HOME}/cmdline-tools"
_SAVED_PWD="$(pwd)"
cd "${ANDROID_HOME}/cmdline-tools"

if [[ ! -d "latest" ]]; then
    CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    info "  Downloading cmdline-tools (~150MB)..."
    wget --progress=bar:force:noscroll "${CMDLINE_URL}" -O cmdline-tools.zip
    echo "  Extracting..."
    unzip -qo cmdline-tools.zip
    mv cmdline-tools latest
    rm -f cmdline-tools.zip
else
    info "  cmdline-tools already exists, skipping."
fi

export ANDROID_HOME="${ANDROID_HOME}"
export PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${PATH}"

# Accept licenses
info "  Accepting SDK licenses..."
yes | sdkmanager --licenses 2>/dev/null || true

# Install required components
info "  Installing platform android-${COMPILE_SDK_VERSION} + build-tools ${BUILD_TOOLS_VERSION}..."
sdkmanager \
    "platforms;android-${COMPILE_SDK_VERSION}" \
    "build-tools;${BUILD_TOOLS_VERSION}"

cd "$_SAVED_PWD"
unset _SAVED_PWD

ok "Android SDK installed (platform ${COMPILE_SDK_VERSION}, build-tools ${BUILD_TOOLS_VERSION})"

# ── Step 4: Android NDK r29 ────────────────────────────────────────────────
info "[4/7] Installing Android NDK ${NDK_VERSION}..."

NDK_HOME="${ANDROID_HOME}/ndk/${NDK_VERSION}"

if [[ ! -d "${NDK_HOME}" ]]; then
    # Try sdkmanager first (usually faster, uses partial downloads)
    if sdkmanager "ndk;${NDK_VERSION}" 2>/dev/null; then
        ok "NDK installed via sdkmanager"
    else
        warn "  sdkmanager failed, falling back to direct download (~2GB)..."
        mkdir -p "${ANDROID_HOME}/ndk"
        NDK_URL="https://dl.google.com/android/repository/android-ndk-${NDK_VERSION}-linux.zip"
        wget --progress=bar:force:noscroll "${NDK_URL}" -O ndk.zip
        echo "  Extracting NDK (this takes a while)..."
        unzip -qo ndk.zip -d "${ANDROID_HOME}/ndk/"
        rm -f ndk.zip
        if [[ -d "${ANDROID_HOME}/ndk/android-ndk-${NDK_VERSION}" ]] && [[ ! -d "${NDK_HOME}" ]]; then
            mv "${ANDROID_HOME}/ndk/android-ndk-${NDK_VERSION}" "${NDK_HOME}"
        fi
    fi
else
    info "  NDK already exists at ${NDK_HOME}, skipping."
fi

export ANDROID_NDK_HOME="${NDK_HOME}"
ok "Android NDK ${NDK_VERSION} at ${NDK_HOME}"

# ── Step 5: Rust + cargo-ndk ───────────────────────────────────────────────
info "[5/7] Installing Rust toolchain + cargo-ndk..."

if ! command -v rustc &> /dev/null; then
    echo "  Installing rustup..."
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
fi

source "${HOME}/.cargo/env"

echo "  Installing cargo-ndk (cross-compilation helper)..."
cargo install cargo-ndk 2>/dev/null || true

echo "  Adding Android target: aarch64-linux-android..."
rustup target add aarch64-linux-android 2>/dev/null || true

ok "Rust $(rustc --version) + cargo-ndk ready"

# ── Step 6: Verify CMake & ccache ──────────────────────────────────────────
info "[6/7] Verifying CMake and ccache..."

CMAKE_VERSION=$(cmake --version 2>/dev/null | head -1 | awk '{print $3}')
CCACHE_VERSION=$(ccache --version 2>/dev/null | head -1)

ok "CMake ${CMAKE_VERSION:-not found}"
ok "ccache ${CCACHE_VERSION:-not found}"

# ── Step 7: Write environment & local.properties ────────────────────────────
info "[7/7] Writing environment configuration..."

BASHRC="${HOME}/.bashrc"

grep -q '# FolkPatch Android SDK' "${BASHRC}" 2>/dev/null || cat >> "${BASHRC}" << 'EOF'

# FolkPatch Android SDK
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
EOF

grep -q '# FolkPatch Java' "${BASHRC}" 2>/dev/null || cat >> "${BASHRC}" << 'EOF'

# FolkPatch Java
export JAVA_HOME="$(dirname $(dirname $(readlink -f $(which java) 2>/dev/null || echo /usr/lib/jvm/java-21-openjdk-amd64/bin/java)))"
EOF

grep -q '# FolkPatch Rust' "${BASHRC}" 2>/dev/null || cat >> "${BASHRC}" << 'EOF'

# FolkPatch Rust
. "$HOME/.cargo/env"
EOF

cat > "${PROJECT_DIR}/local.properties" << EOF
# Auto-generated by setup-wsl.sh
sdk.dir=${ANDROID_HOME}
debug.fake_root=false
EOF

ok "Environment written to ~/.bashrc"
ok "local.properties created at ${PROJECT_DIR}/local.properties"

# ── Summary ─────────────────────────────────────────────────────────────────
echo ""
echo "=========================================="
echo -e "${GREEN}FolkPatch WSL Setup Complete!${NC}"
echo "=========================================="
echo ""
echo "Installed components:"
echo "  JDK          : $(java -version 2>&1 | head -1)"
echo "  Android SDK  : ${ANDROID_HOME}"
echo "  NDK          : ${NDK_VERSION} at ${NDK_HOME:-${ANDROID_HOME}/ndk/${NDK_VERSION}}"
echo "  Rust         : $(rustc --version 2>/dev/null || echo 'not found')"
echo "  cargo-ndk    : $(cargo ndk --version 2>/dev/null || echo 'not found')"
echo "  CMake        : $(cmake --version 2>/dev/null | head -1 || echo 'not found')"
echo "  ccache       : $(ccache --version 2>/dev/null | head -1 || echo 'not found')"
echo ""
echo "Next steps:"
echo "  1. Close and reopen terminal (or: source ~/.bashrc)"
echo "  2. Open VS Code: code '${PROJECT_DIR}'"
echo "  3. Build debug:  bash scripts/Build-Debug.sh"
echo "  4. Build release: bash scripts/Build-Release.sh"
echo ""
