#!/usr/bin/env bash
# Env del toolchain local para fish. Uso:  source scripts/env.fish
set -gx HUGINN_ROOT (realpath (dirname (status --current-filename))/..)
set -gx ANDROID_HOME $HUGINN_ROOT/.toolchain/sdk
set -gx ANDROID_SDK_ROOT $ANDROID_HOME
set -gx ANDROID_USER_HOME $HUGINN_ROOT/.toolchain/home/.android
set -gx GRADLE_USER_HOME $HUGINN_ROOT/.gradle-home
fish_add_path --prepend $HUGINN_ROOT/.toolchain/gradle-9.8.2/bin
fish_add_path --prepend $ANDROID_HOME/platform-tools
echo "Huginn: toolchain listo (SDK en $ANDROID_HOME)"
