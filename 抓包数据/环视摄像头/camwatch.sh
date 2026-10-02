#!/bin/bash
# camwatch.sh [tag] -- snapshot who holds the 4 surround cameras, with frame counters
tag=${1:-snap}
out="camwatch_${tag}_$(date +%H%M%S).txt"
{
  echo "=== $(date '+%F %T') tag=$tag ==="
  adb shell dumpsys media.camera | grep -E \
    'Camera ID:|Client Package Name|Frames produced|Dims:|Conflicting|Number of camera'
  echo "--- foreground ---"
  adb shell dumpsys window | grep mCurrentFocus | grep -v "null"
  echo "--- gear (cmdId 26) via recent log ---"
  adb shell "logcat -d -t 400" | grep -E "keyGear|mGearPosition|onGearStateChange|rvc status|video status|AVM" | tail -15
} > "$out" 2>&1
echo "-> $out"
tail -25 "$out"
