#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 2 ]]; then
  echo 'Usage: check-install-uninstall.sh DEVICE_SERIAL APK_PATH' >&2
  exit 2
fi

serial=$1
apk=$2
package=com.tangem.usdtrecovery.preview
adb -s "$serial" get-state
if adb -s "$serial" shell pm list packages "$package" | tr -d '\r' | grep -Fxq "package:$package"; then
  echo 'App is already installed. Use a clean test device or uninstall it manually first.' >&2
  exit 1
fi

adb -s "$serial" install "$apk"
launch_result=$(adb -s "$serial" shell am start -W -n "$package/com.tangem.usdtrecovery.MainActivity")
printf '%s\n' "$launch_result"
if ! printf '%s\n' "$launch_result" | tr -d '\r' | grep -Fxq 'Status: ok'; then
  echo 'FAIL: launch command did not report success. Test install remains on device.' >&2
  exit 1
fi
adb -s "$serial" uninstall "$package"
if adb -s "$serial" shell pm list packages "$package" | tr -d '\r' | grep -Fxq "package:$package"; then
  echo 'FAIL: package is still installed.' >&2
  exit 1
fi
echo 'PASS: APK installed, launch command completed, and package uninstalled. Check logcat for startup errors.'
