#!/usr/bin/env bash
set -euo pipefail

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
if [[ ! -d "$sdk_root/platforms/android-37.0" ]]; then
  echo "Android SDK platform 37.0 not found at: $sdk_root/platforms/android-37.0" >&2
  echo "Set ANDROID_SDK_ROOT or install platforms;android-37.0 under $HOME/Android/Sdk." >&2
  exit 2
fi

export ANDROID_SDK_ROOT="$sdk_root"
export ANDROID_HOME="$sdk_root"

gradle_bin="${GRADLE_BIN:-$(command -v gradle || true)}"
if [[ -z "$gradle_bin" || ! -x "$gradle_bin" ]]; then
  echo "Gradle 9.3.1+ is required for AGP 9.1.1." >&2
  exit 3
fi

version="$("$gradle_bin" --version | awk '/^Gradle / { print $2; exit }')"
major="${version%%.*}"
rest="${version#*.}"
minor="${rest%%.*}"
if (( major < 9 || (major == 9 && minor < 3) )); then
  echo "Gradle 9.3.1+ is required for AGP 9.1.1; found $version at $gradle_bin." >&2
  exit 4
fi

exec "$gradle_bin" "$@"
