#!/usr/bin/env bash
set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
AVDMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
AVD_NAME="flip7_angle_api35"
IMAGE="system-images;android-35;google_apis;x86_64"
DEVICE="6.7in Foldable"
AVD_DIR="$HOME/.android/avd/$AVD_NAME.avd"

if [[ ! -x "$AVDMANAGER" ]]; then
  echo "avdmanager not found at $AVDMANAGER" >&2
  exit 2
fi

if [[ ! -d "$SDK_ROOT/system-images/android-35/google_apis/x86_64" ]]; then
  echo "Required system image is missing: $IMAGE" >&2
  exit 3
fi

verify_existing() {
  local config="$AVD_DIR/config.ini"
  [[ -f "$config" ]] || return 1
  grep -Eq '^image\.sysdir\.1=system-images/android-35/google_apis/x86_64/?$' "$config" || return 1
  grep -Eq '^hw\.device\.name=6\.7in Foldable$' "$config" || return 1
}

if [[ -d "$AVD_DIR" ]]; then
  if verify_existing; then
    echo "$AVD_NAME already exists with the expected image/profile."
    exit 0
  fi
  echo "Refusing to overwrite existing mismatched AVD: $AVD_DIR" >&2
  exit 4
fi

printf 'no\n' | "$AVDMANAGER" create avd \
  --name "$AVD_NAME" \
  --package "$IMAGE" \
  --device "$DEVICE"

if ! verify_existing; then
  echo "Created AVD does not match the requested image/profile; refusing to continue." >&2
  exit 5
fi

echo "Created $AVD_NAME at $AVD_DIR"
