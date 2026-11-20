#!/usr/bin/env bash
set -euo pipefail

LOCK_FILE="/tmp/flip7-angle-emulator-5580.lock"
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  echo "QA ERROR: another run-emulator-qa.sh already owns emulator-5580" >&2
  exit 75
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
ADB="$SDK_ROOT/platform-tools/adb"
EMULATOR="$SDK_ROOT/emulator/emulator"
SERIAL="emulator-5580"
PORT="5580"
AVD_NAME="flip7_angle_api35"
PACKAGE="com.mentoster.flip7anglelab"
RUNNER="$PACKAGE.test/com.mentoster.flip7anglelab.test.Flip7SmokeInstrumentation"
LOG_FILE="/tmp/flip7-angle-emulator-5580.log"

die() {
  echo "QA ERROR: $*" >&2
  exit 1
}

avd_name_for_serial() {
  "$ADB" -s "$SERIAL" emu avd name 2>/dev/null | head -n 1 | tr -d '\r'
}

guard_serial() {
  local state
  state=$("$ADB" -s "$SERIAL" get-state 2>/dev/null || true)
  [[ "$state" == "device" ]] || die "$SERIAL is not an online emulator"
  local name
  name=$(avd_name_for_serial)
  [[ "$name" == "$AVD_NAME" ]] || die "$SERIAL belongs to '$name', expected '$AVD_NAME'"
}

boot_ready() {
  local booted package_state settings_state provisioned
  booted=$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  package_state=$("$ADB" -s "$SERIAL" shell service check package 2>/dev/null | tr -d '\r')
  settings_state=$("$ADB" -s "$SERIAL" shell service check settings 2>/dev/null | tr -d '\r')
  provisioned=$("$ADB" -s "$SERIAL" shell settings get global device_provisioned 2>/dev/null | tr -d '\r')
  [[ "$booted" == "1" &&
     "$package_state" == "Service package: found" &&
     "$settings_state" == "Service settings: found" &&
     "$provisioned" =~ ^[01]$ ]]
}

wait_for_boot() {
  for _ in $(seq 1 600); do
    boot_ready && {
      guard_serial
      return 0
    }
    sleep 1
  done
  tail -n 80 "$LOG_FILE" >&2 || true
  die "$SERIAL did not finish booting"
}

if "$ADB" devices | awk -v serial="$SERIAL" '$1 == serial { found=1 } END { exit !found }'; then
  guard_serial
  echo "Reusing verified $AVD_NAME on $SERIAL"
else
  accel=("-accel" "off")
  if [[ -r /dev/kvm && -w /dev/kvm ]]; then
    accel=("-accel" "on")
  fi

  echo "Starting $AVD_NAME on $SERIAL (${accel[*]}, SwiftShader GPU)"
  nohup setsid "$EMULATOR" \
    -avd "$AVD_NAME" \
    -port "$PORT" \
    -no-window \
    -no-snapshot \
    -no-audio \
    -no-boot-anim \
    -cores 2 \
    -memory 3072 \
    -gpu swiftshader \
    "${accel[@]}" </dev/null >"$LOG_FILE" 2>&1 &

  for _ in $(seq 1 120); do
    if "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
  "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1 || {
    tail -n 80 "$LOG_FILE" >&2 || true
    die "$SERIAL did not appear"
  }

fi

wait_for_boot

bash scripts/gradle-local.sh :app:assembleDebug :app:assembleDebugAndroidTest
guard_serial

install_apk() {
  local apk="$1"
  local attempt
  for attempt in 1 2 3; do
    wait_for_boot
    guard_serial
    if "$ADB" -s "$SERIAL" install --no-streaming -r "$apk"; then
      return 0
    fi
    echo "Install attempt $attempt failed for $apk; waiting for Android services to settle." >&2
    sleep 10
  done
  die "Failed to install $apk after 3 attempts"
}

install_apk app/build/outputs/apk/debug/app-debug.apk
install_apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ADB" -s "$SERIAL" shell am force-stop "$PACKAGE"
"$ADB" -s "$SERIAL" shell am start -W -n "$PACKAGE/.MainActivity"
guard_serial
instrument_output=$("$ADB" -s "$SERIAL" shell am instrument -w -r "$RUNNER")
printf '%s\n' "$instrument_output"
grep -q 'smoke=PASS' <<<"$instrument_output" || die "Smoke instrumentation did not report PASS"

echo "QA passed on $AVD_NAME ($SERIAL). Emulator log: $LOG_FILE"
