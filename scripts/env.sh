#!/usr/bin/env bash
# Entorno del toolchain local de Huginn. Uso (bash):  source scripts/env.sh
# En fish:  source scripts/env.fish
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"

export HUGINN_ROOT="$ROOT"
export ANDROID_HOME="$ROOT/.toolchain/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$ROOT/.toolchain/home/.android"
export GRADLE_USER_HOME="$ROOT/.gradle-home"
export PATH="$ROOT/.toolchain/gradle-9.8.2/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# adb necesita $HOME escribible para sus claves; el HOME real es de solo lectura
# para el sandbox del agente.
export HUGINN_ANDROID_HOME="$ROOT/.toolchain/home"
