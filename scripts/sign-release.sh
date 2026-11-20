#!/usr/bin/env bash
set -euo pipefail

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
# A fresh Android application ID starts a new signing identity.
# Specify keystore and password-file paths explicitly; never commit them.
keystore="${FLIP7_ANGLE_KEYSTORE:?Set FLIP7_ANGLE_KEYSTORE to your release keystore path}"
password_file="${FLIP7_ANGLE_KEYSTORE_PASSWORD_FILE:?Set FLIP7_ANGLE_KEYSTORE_PASSWORD_FILE to your password file path}"
key_alias="${FLIP7_ANGLE_KEY_ALIAS:-flip7anglelab}"
unsigned_apk="${1:-app/build/outputs/apk/release/app-release-unsigned.apk}"
metadata_file="app/build/outputs/apk/release/output-metadata.json"
if [[ -n "${2:-}" ]]; then
  signed_apk="$2"
elif [[ -f "$metadata_file" ]]; then
  version_name="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["elements"][0]["versionName"])' "$metadata_file")"
  signed_apk="app/build/outputs/apk/release/Flip7AngleLab-${version_name}-release.apk"
else
  signed_apk="app/build/outputs/apk/release/Flip7AngleLab-release.apk"
fi

if [[ ! -f "$keystore" ]]; then
  echo "Release keystore not found: $keystore" >&2
  exit 2
fi
if [[ ! -f "$password_file" ]]; then
  echo "Release password file not found: $password_file" >&2
  exit 3
fi
if [[ ! -f "$unsigned_apk" ]]; then
  echo "Unsigned APK not found: $unsigned_apk" >&2
  exit 4
fi

shopt -s nullglob
apksigners=("$sdk_root"/build-tools/*/apksigner)
shopt -u nullglob
if [[ ${#apksigners[@]} -eq 0 ]]; then
  echo "apksigner not found under: $sdk_root/build-tools" >&2
  exit 5
fi
apksigner="$(printf '%s\n' "${apksigners[@]}" | sort -V | tail -n 1)"

password="$(<"$password_file")"
export FLIP7_ANGLE_SIGNING_PASSWORD="$password"
trap 'unset FLIP7_ANGLE_SIGNING_PASSWORD password' EXIT

"$apksigner" sign \
  --ks "$keystore" \
  --ks-key-alias "$key_alias" \
  --ks-pass env:FLIP7_ANGLE_SIGNING_PASSWORD \
  --key-pass env:FLIP7_ANGLE_SIGNING_PASSWORD \
  --out "$signed_apk" \
  "$unsigned_apk"

"$apksigner" verify --verbose --print-certs "$signed_apk"
sha256sum "$signed_apk"
