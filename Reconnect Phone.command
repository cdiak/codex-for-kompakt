#!/bin/zsh
set -eu
SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
ADB="${KOMPAKT_ADB:-$SCRIPT_DIR/work/toolchain/android/platform-tools/adb}"
"$ADB" reverse tcp:39443 tcp:39443
"$ADB" shell am start -n org.kompakt.remote/.MainActivity
echo 'USB connection restored. The Mac companion must also be running.'
