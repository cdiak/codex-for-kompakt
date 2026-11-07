#!/bin/zsh
set -eu
SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
TAILSCALE="${KOMPAKT_TAILSCALE:-/Applications/Tailscale.app/Contents/MacOS/Tailscale}"
CODEX_BIN="${KOMPAKT_CODEX:-$(command -v codex || true)}"
if [[ -z "$CODEX_BIN" ]]; then
  CODEX_BIN="/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex"
fi
RUNTIME_DIR="${KOMPAKT_RUNTIME:-$SCRIPT_DIR/work/kompakt-runtime}"
PROJECT_DIR="${KOMPAKT_WORKSPACE:-$SCRIPT_DIR/work/demo-project}"
cd "$SCRIPT_DIR"
"$TAILSCALE" serve --bg --tcp=39443 tcp://127.0.0.1:39443
echo 'Starting the private remote companion. Keep Tailscale connected on both devices.'
echo 'This keeps the Mac awake until Control-C; the display can still sleep.'
export PYTHONDONTWRITEBYTECODE=1
exec /usr/bin/caffeinate -i python3 "$SCRIPT_DIR/companion.py" \
  --runtime "$RUNTIME_DIR" --workspace "$PROJECT_DIR" --codex "$CODEX_BIN" --bind 127.0.0.1
