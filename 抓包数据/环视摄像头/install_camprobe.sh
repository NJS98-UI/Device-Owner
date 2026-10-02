#!/bin/bash
# 装 CamProbe：这台 ROM 拦 shell uid 的安装，必须走正常安装器（MT 管理器）
set -u
ADB="/c/Users/Administrator/AndroidSDK/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1
cd "/c/Users/Administrator/AppData/Local/Temp/yibiao/源码/camprobe" || exit 1

"$ADB" push CamProbe.apk /sdcard/Download/CamProbe.apk 2>&1 | tail -1
URI='file:///sdcard/Download/CamProbe.apk'
"$ADB" shell am start -n bin.mt.plus/.OpenFileActivity -d "$URI" \
  -t application/vnd.android.package-archive 2>&1 | head -5
echo "-> 车机上应该弹出 MT 管理器的安装界面，点『安装』，装完说一声"
