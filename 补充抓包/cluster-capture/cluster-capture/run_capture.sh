#!/usr/bin/env bash
set -euo pipefail

OUT="cluster_capture_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUT"

echo "=== 仪表投屏协议一键抓包 ==="
echo "输出目录: $OUT"
echo ""
echo "请确认电脑已连接车机且 adb devices 能看到设备。"
read -r -p "按回车继续..."

adb_root() {
  if adb shell "su -c id" >/dev/null 2>&1; then
    adb shell "su -c $*"
  else
    adb shell "$@"
  fi
}

echo ""
echo "[1/8] 获取系统信息 getprop"
adb_root "getprop" > "$OUT/getprop.txt"

echo "[2/8] 查找高德/导航相关包"
adb_root "pm list packages" | grep -iE "amap|autonavi|navi|nav|gaode|cluster|instrument|hmi|ivi|oem" > "$OUT/packages.txt" || true

echo "[3/8] 获取 display 信息"
adb_root "dumpsys display" > "$OUT/display.txt"

echo "[4/8] 获取进程列表"
adb_root "ps -A" > "$OUT/processes.txt"

# Pick candidate package
PKG=""
for cand in $(grep -iE "amap|autonavi|navi" "$OUT/packages.txt" 2>/dev/null | awk -F':' '{print $2}' | sort -u); do
  if adb_root "pidof $cand" >/dev/null 2>&1; then
    PID="$(adb_root "pidof $cand" 2>/dev/null | awk '{print $1}')"
    if [ -n "$PID" ]; then
      PKG="$cand"
      echo "发现运行中的导航包: $PKG PID=$PID"
      break
    fi
  fi
done
if [ -z "$PKG" ]; then
  PKG="$(head -n 1 "$OUT/packages.txt" 2>/dev/null | awk -F':' '{print $2}' | tr -d ' ' || true)"
fi
if [ -z "$PKG" ]; then
  echo "警告: 未找到导航相关包，将跳过 strace。请先启动高德车机版后再运行脚本。"
fi

echo "[5/8] 准备 logcat 与 strace 后台记录"
adb_root "logcat -c"
LOGPID=$(adb_root "sh -c 'logcat -v time -f /sdcard/cluster_capture.log & echo \$!'" | tr -d '\r')
STRACE_PID=""
if [ -n "$PKG" ]; then
  PID_NOW="$(adb_root "pidof $PKG" | awk '{print $1}')"
  if [ -n "$PID_NOW" ] && adb_root "command -v strace" >/dev/null 2>&1; then
    STRACE_PID=$(adb_root "sh -c \"strace -f -e trace=open,openat,connect,bind,listen,sendto,recvfrom -p $PID_NOW -o /sdcard/cluster_strace.txt & echo \$!\"" | tr -d '\r')
  else
    echo "未找到 strace 或包进程，跳过 strace。"
  fi
fi

echo ""
echo "现在请在车机上执行投屏到仪表，保持 20 秒，再取消投屏，等 10 秒。"
read -r -p "完成后按回车继续..."

echo "[6/8] 停止记录并拉取文件"
if [ -n "$LOGPID" ]; then adb_root "kill $LOGPID" 2>/dev/null || true; fi
if [ -n "$STRACE_PID" ]; then adb_root "kill $STRACE_PID" 2>/dev/null || true; fi
sleep 2
adb_root "cp /sdcard/cluster_capture.log /sdcard/cluster_capture_full.log 2>/dev/null || true"
adb_root "grep -iE 'amap|autonavi|navi|cluster|instrument|display|mirror|projection|window|surface|binder|aidl|socket|bind|connect' /sdcard/cluster_capture_full.log > /sdcard/cluster_capture_filtered.log 2>/dev/null || true"
adb_root "mv /sdcard/cluster_capture.log /sdcard/cluster_capture_full.log 2>/dev/null || true"
adb pull /sdcard/cluster_capture_full.log "$OUT/logcat_cluster.log" || true
adb pull /sdcard/cluster_capture_filtered.log "$OUT/logcat_cluster_filtered.log" || true
adb pull /sdcard/cluster_strace.txt "$OUT/strace_amap.txt" || true

echo "[7/8] 尝试抓本机 tcpdump"
if adb_root "command -v tcpdump" >/dev/null 2>&1; then
  adb_root "tcpdump -i lo -s 0 -w /sdcard/cluster_local.cap 2>/dev/null & TCPDUMP_PID=\$!; sleep 15; kill \$TCPDUMP_PID 2>/dev/null || true" || true
  adb pull /sdcard/cluster_local.cap "$OUT/tcpdump_local.cap" || true
else
  echo "车机没有 tcpdump，跳过。"
fi

echo "[8/8] 生成 summary 并打包"
{
  echo "capture_time: $(date '+%Y-%m-%d %H:%M:%S')"
  echo "target_package: ${PKG:-unknown}"
  echo "files:"
  ls -1 "$OUT"
} > "$OUT/summary.txt"

cd "$OUT"
zip -r "../$OUT.zip" . >/dev/null
cd ..
rm -rf "$OUT"
echo ""
echo "完成: $OUT.zip"
echo "请把这个 zip 发给我，我继续分析协议。"
