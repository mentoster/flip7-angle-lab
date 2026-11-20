#!/usr/bin/env bash
set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
ADB="$SDK_ROOT/platform-tools/adb"
SERIAL="emulator-5580"
AVD_NAME="flip7_angle_api35"

guard_serial() {
  local state name
  state=$("$ADB" -s "$SERIAL" get-state 2>/dev/null || true)
  [[ "$state" == "device" ]] || { echo "$SERIAL is not online" >&2; exit 2; }
  name=$("$ADB" -s "$SERIAL" emu avd name 2>/dev/null | head -n 1 | tr -d '\r')
  [[ "$name" == "$AVD_NAME" ]] || {
    echo "Refusing to control $SERIAL: it is '$name', not '$AVD_NAME'" >&2
    exit 3
  }
}

for angle in 180 135 90 45; do
  guard_serial
  echo "hinge=$angle"
  "$ADB" -s "$SERIAL" emu sensor set hinge-angle0 "$angle"
  sleep 1
done
