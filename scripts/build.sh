#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$task_root"
task_variant=debug
if [[ "${1:-}" == debug || "${1:-}" == release ]]; then
  task_variant="$1"
  shift
fi
task_output="artifacts/$task_variant"
mkdir -p "$task_output"
rm -f "$task_output/AIR.apk" "$task_output/SHA256SUMS"
mkdir -p .tools
if [[ -z "${JAVA_HOME:-}" && -d "$task_root/.tools/jdk/Contents/Home" ]]; then
  export JAVA_HOME="$task_root/.tools/jdk/Contents/Home"
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$task_root/.tools/android-sdk" ]]; then
  export ANDROID_HOME="$task_root/.tools/android-sdk"
fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$task_root/.tools/gradle-user}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$task_root/.tools/android-user}"
if [[ "$task_variant" == release ]]; then
  for task_setting in AIR_KEYSTORE_PATH AIR_KEYSTORE_PASSWORD AIR_KEY_ALIAS AIR_KEY_PASSWORD; do
    if [[ -z "${!task_setting:-}" ]]; then
      printf 'Release signing requires %s. No APK was published.\n' "$task_setting" >&2
      exit 1
    fi
  done
  if [[ ! -f "$AIR_KEYSTORE_PATH" ]]; then
    printf 'Release keystore does not exist. No APK was published.\n' >&2
    exit 1
  fi
elif [[ ! -f "$task_root/.tools/debug.keystore" ]]; then
  task_keytool="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
  "$task_keytool" -genkeypair -keystore "$task_root/.tools/debug.keystore" \
    -storepass android -keypass android -alias androiddebugkey \
    -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
fi
if [[ "$task_variant" == debug ]]; then task_gradle_variant=Debug; else task_gradle_variant=Release; fi
./gradlew :navigation:test :updates:test ":app:test${task_gradle_variant}UnitTest" ":app:lint$task_gradle_variant" ":app:assemble$task_gradle_variant" --console=plain "$@"
task_apk="app/build/outputs/apk/$task_variant/app-$task_variant.apk"
task_abis="$(unzip -Z -1 "$task_apk" | awk -F/ '$1 == "lib" && NF == 3 && $3 ~ /\.so$/ { print $2 }' | sort -u)"
if [[ "$task_abis" != arm64-v8a ]]; then
  printf 'Expected only arm64-v8a native libraries. No APK was published.\n' >&2
  exit 1
fi
cp "$task_apk" "$task_output/AIR.apk"
(
  cd "$task_output"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum AIR.apk > SHA256SUMS
  else
    shasum -a 256 AIR.apk > SHA256SUMS
  fi
)
