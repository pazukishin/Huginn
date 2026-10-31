#!/usr/bin/env bash
# Huginn — bootstrap del toolchain Android local, sin tocar el sistema.
#
# Instala en .toolchain/ (gitignored):
#   - Gradle 9.8.2 (distribución bin)
#   - Android SDK cmdline-tools + platform-tools + platforms;android-36 + build-tools;36.0.0
#   - HOME propio para las herramientas (licencias, claves adb) => .toolchain/home
#
# Requisitos: JDK 17+ en PATH (probado con JDK 27), curl, unzip.
# Uso:  bash scripts/bootstrap-env.sh
# Idempotente: se puede relanzar; solo descarga lo que falte.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOL="$ROOT/.toolchain"
SDK="$TOOL/sdk"
TOOL_HOME="$TOOL/home"
GRADLE_VERSION="9.8.2"
CMDTOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip"
PACKAGES=("platform-tools" "platforms;android-36" "build-tools;36.0.0")

mkdir -p "$TOOL" "$TOOL_HOME"

# ---- Gradle -----------------------------------------------------------------
if [ ! -x "$TOOL/gradle-$GRADLE_VERSION/bin/gradle" ]; then
  echo "[bootstrap] descargando Gradle $GRADLE_VERSION ..."
  curl -fL --retry 3 -o "$TOOL/gradle.zip" \
    "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  unzip -q -o "$TOOL/gradle.zip" -d "$TOOL"
  rm -f "$TOOL/gradle.zip"
fi

# ---- Android cmdline-tools --------------------------------------------------
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "[bootstrap] descargando Android cmdline-tools ..."
  mkdir -p "$SDK/cmdline-tools" "$TOOL/tmp"
  curl -fL --retry 3 -o "$TOOL/tmp/cmdtools.zip" "$CMDTOOLS_URL"
  rm -rf "$TOOL/tmp/cmdline-tools"
  unzip -q -o "$TOOL/tmp/cmdtools.zip" -d "$TOOL/tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$TOOL/tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -f "$TOOL/tmp/cmdtools.zip"
fi

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_USER_HOME="$TOOL_HOME/.android"
export HOME="$TOOL_HOME"
export PATH="$TOOL/gradle-$GRADLE_VERSION/bin:$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"

echo "[bootstrap] aceptando licencias ..."
yes | sdkmanager --sdk_root="$SDK" --licenses >/dev/null || true

echo "[bootstrap] instalando paquetes: ${PACKAGES[*]} ..."
sdkmanager --sdk_root="$SDK" --install "${PACKAGES[@]}"

# ---- local.properties -------------------------------------------------------
printf 'sdk.dir=%s\n' "$SDK" > "$ROOT/local.properties"

echo "[bootstrap] listo."
echo "  ANDROID_HOME=$SDK"
echo "  GRADLE_USER_HOME=$ROOT/.gradle-home (usar scripts/env.sh)"
"$TOOL/gradle-$GRADLE_VERSION/bin/gradle" --version | sed -n '1,8p'
