#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$task_root"
mkdir -p .tools
if [[ -z "${JAVA_HOME:-}" && -d "$task_root/.tools/jdk/Contents/Home" ]]; then
  export JAVA_HOME="$task_root/.tools/jdk/Contents/Home"
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$task_root/.tools/android-sdk" ]]; then
  export ANDROID_HOME="$task_root/.tools/android-sdk"
fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$task_root/.tools/gradle-user}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$task_root/.tools/android-user}"
if [[ ! -f "$task_root/.tools/debug.keystore" ]]; then
  task_keytool="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
  "$task_keytool" -genkeypair -keystore "$task_root/.tools/debug.keystore" \
    -storepass android -keypass android -alias androiddebugkey \
    -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
fi
./gradlew :navigation:test :app:lintDebug :app:assembleDebug --console=plain "$@"
mkdir -p artifacts
cp app/build/outputs/apk/debug/app-arm64-v8a-debug.apk artifacts/AIR-arm64-test.apk
cp app/build/outputs/apk/debug/app-x86_64-debug.apk artifacts/AIR-x86_64-test.apk
