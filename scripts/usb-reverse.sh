#!/bin/sh
set -eu

SDK_DIR=$(sed -n 's/^sdk.dir=//p' "$(dirname "$0")/../local.properties" | head -n 1)
ADB="$SDK_DIR/platform-tools/adb"

if [ ! -x "$ADB" ]; then
  echo "找不到 adb，请确认 android/local.properties 中的 sdk.dir 配置正确" >&2
  exit 1
fi

DEVICE_COUNT=$($ADB devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')
if [ "$DEVICE_COUNT" -ne 1 ]; then
  echo "需要且只能连接一台已授权的 Android 设备，当前检测到 $DEVICE_COUNT 台" >&2
  exit 1
fi

$ADB reverse tcp:8000 tcp:8000
echo "USB 转发已开启：手机 127.0.0.1:8000 -> 本机 127.0.0.1:8000"
