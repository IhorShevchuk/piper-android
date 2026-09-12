#!/bin/bash
# One-time setup: JDK 17 + Android SDK (platform, build-tools, NDK r27, CMake)
# on this Linux box so `gradlew assembleDebug` works here.
# Re-run safely: skips steps that are already done.
set -euo pipefail

SDK="$HOME/workspace/android-sdk"
CMDLINE_ZIP_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

echo "==> Installing JDK 17 + unzip"
sudo apt-get update -qq
sudo apt-get install -y -qq openjdk-17-jdk-headless unzip > /dev/null
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
java -version

echo "==> Setting up Android SDK at $SDK"
mkdir -p "$SDK/cmdline-tools"
if [ ! -d "$SDK/cmdline-tools/latest" ]; then
    curl -L -o /tmp/cmdtools.zip "$CMDLINE_ZIP_URL"
    rm -rf /tmp/cmdtools-extract && mkdir -p /tmp/cmdtools-extract
    unzip -q /tmp/cmdtools.zip -d /tmp/cmdtools-extract
    mv /tmp/cmdtools-extract/cmdline-tools "$SDK/cmdline-tools/latest"
    rm -rf /tmp/cmdtools-extract /tmp/cmdtools.zip
fi

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"

echo "==> Accepting licenses"
yes | sdkmanager --licenses > /dev/null || true

echo "==> Installing SDK packages (this takes a while)"
sdkmanager --install \
    "platform-tools" \
    "platforms;android-35" \
    "build-tools;35.0.0" \
    "ndk;27.0.12077973" \
    "cmake;3.22.1"

echo "==> Done. SDK=$SDK"
echo "    NDK: $SDK/ndk/27.0.12077973"
ls "$SDK"
