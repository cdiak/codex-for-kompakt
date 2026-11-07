#!/bin/zsh
set -eu
SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
ADB="${KOMPAKT_ADB:-$SCRIPT_DIR/work/toolchain/android/platform-tools/adb}"
CODEX_BIN="${KOMPAKT_CODEX:-$(command -v codex || true)}"
if [[ -z "$CODEX_BIN" ]]; then
  CODEX_BIN="/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex"
fi
RUNTIME_DIR="${KOMPAKT_RUNTIME:-$SCRIPT_DIR/work/kompakt-runtime}"
PROJECT_DIR="${KOMPAKT_WORKSPACE:-$SCRIPT_DIR/work/demo-project}"
cd "$SCRIPT_DIR"
"$ADB" reverse tcp:39443 tcp:39443
echo 'Starting Codex for Kompakt. Keep this window open; press Control-C to stop.'
PYTHONPYCACHEPREFIX="$SCRIPT_DIR/work/pycache" python3 "$SCRIPT_DIR/companion.py" \
  --runtime "$RUNTIME_DIR" \
  --workspace "$PROJECT_DIR" \
  --codex "$CODEX_BIN" --bind 127.0.0.1
