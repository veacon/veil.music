#!/bin/sh
# adb 包装：每次调用都会重启 adb server（沙箱会在命令结束时杀掉它），所以先确保设备在线。
export MSYS_NO_PATHCONV=1
ADB="/c/devtools/android-sdk/platform-tools/adb.exe"
$ADB start-server >/dev/null 2>&1
i=0
while [ $i -lt 25 ]; do
  st=$($ADB get-state 2>/dev/null)
  [ "$st" = "device" ] && break
  sleep 1
  i=$((i+1))
done
exec $ADB "$@"
