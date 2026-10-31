#!/usr/bin/env bash
# adb con HOME propio + reconexión automática a la Thor.
#
# El sandbox de dsh aísla el PID namespace por comando: el servidor adb muere al
# terminar cada invocación, así que hay que reconectar siempre. Guarda el destino
# en .tmp/adb-target (una línea: IP:PUERTO) o pásalo por HUGINN_ADB_TARGET.
#
#   scripts/device.sh                # estado + reconexión
#   scripts/device.sh shell dumpsys display
#   scripts/device.sh install -r app/build/outputs/apk/debug/app-debug.apk
#   scripts/device.sh pair IP:PUERTO CODIGO   # emparejamiento (una vez)
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export HOME="$ROOT/.toolchain/home"
mkdir -p "$HOME" "$ROOT/.tmp"
ADB="$ROOT/.toolchain/sdk/platform-tools/adb"

if [ "${1:-}" = "pair" ]; then
  shift
  exec "$ADB" pair "$@"
fi

TARGET="${HUGINN_ADB_TARGET:-$(cat "$ROOT/.tmp/adb-target" 2>/dev/null || true)}"
if [ -n "$TARGET" ]; then
  "$ADB" connect "$TARGET" >/dev/null 2>&1 || true
  # El sandbox relanza el servidor adb en cada comando: esperar al transporte evita
  # que la primera orden (sobre todo `input`) se pierda en la carrera del connect.
  timeout 10 "$ADB" -s "$TARGET" wait-for-device >/dev/null 2>&1 || true
fi

if [ "$#" -eq 0 ]; then
  exec "$ADB" devices -l
fi
if [ -n "$TARGET" ]; then
  exec "$ADB" -s "$TARGET" "$@"
fi
exec "$ADB" "$@"
