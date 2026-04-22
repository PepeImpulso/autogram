#!/usr/bin/env bash
# Starts the Android Auto Desktop Head Unit (DHU) — Google's official
# AA emulator — against a USB-tethered phone, so we can verify whether
# Autogram shows up in the Auto launcher without needing the real car.
#
# Prereqs on the PHONE (one-time):
#   1. Install "Android Auto" from Play Store.
#   2. Open it → tap the app version 10× to unlock Developer Settings.
#   3. Developer Settings → enable "Unknown sources" (lets sideloaded
#      APKs appear in the Auto launcher).
#   4. Developer Settings → "Start head unit server" — this opens a
#      socket on port 5277 that DHU connects to.
#   5. Enable USB debugging on the phone and plug it in.
#
# Usage:   bash scripts/start_dhu.sh
#
# If you leave DHU open and rebuild Autogram, just re-install the APK via
# `adb install -r ...` — AA picks up the new service metadata after a
# reconnect. If the Auto launcher still doesn't show the app, check the
# phone's logcat for `AutogramCarAppService onCreate` — presence confirms
# AA instantiated the service; absence means the manifest filter isn't
# being matched (usually because "Unknown sources" got toggled off).
set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-/c/Android/sdk}"
ADB="$SDK_ROOT/platform-tools/adb.exe"
DHU="$SDK_ROOT/extras/google/auto/desktop-head-unit.exe"

if [[ ! -x "$ADB" ]]; then
    echo "ERROR: adb not found at $ADB" >&2
    exit 1
fi
if [[ ! -x "$DHU" ]]; then
    echo "ERROR: DHU not installed. Run:" >&2
    echo "  \"\$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager.bat\" --install \"extras;google;auto\"" >&2
    exit 1
fi

DEVICES=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
if [[ -z "$DEVICES" ]]; then
    echo "ERROR: no USB-debug device connected. Plug in the phone, accept the RSA prompt, and retry." >&2
    exit 1
fi
echo "[dhu] using device: $DEVICES"

echo "[dhu] forwarding tcp:5277 → phone"
"$ADB" forward tcp:5277 tcp:5277

echo "[dhu] launching desktop-head-unit.exe"
echo "[dhu] on the phone: Android Auto → Developer Settings → Start head unit server"
echo "[dhu] then the DHU window should show Autogram in the launcher grid."
exec "$DHU"
