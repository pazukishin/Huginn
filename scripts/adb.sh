#!/usr/bin/env bash
# adb con HOME propio del toolchain.
#
# El HOME real del usuario es de solo lectura dentro del sandbox del agente, así que
# adb necesita un HOME escribible para sus claves (y así las guardamos en el repo,
# fuera de git). Uso:
#
#   scripts/adb.sh devices
#   scripts/adb.sh pair 192.0.2.42:37123 123456
#   scripts/adb.sh connect 192.0.2.42:5555
#   scripts/adb.sh install -r app/build/outputs/apk/debug/app-debug.apk
#   scripts/adb.sh shell dumpsys display
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export HOME="$ROOT/.toolchain/home"
mkdir -p "$HOME"
exec "$ROOT/.toolchain/sdk/platform-tools/adb" "$@"
