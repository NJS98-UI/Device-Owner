#!/bin/sh
# 仪表铺满黑边取证：用 adb 逐条驱动 App 的 qnx 入口，把 0x1004 的每一种取值各发一次，
# 每条都留三样回读证据——Android 侧 display 2 截图、SurfaceFlinger 图层边界、cabinlan 日志原文，
# 再打印一个墙上时刻表，方便对着仪表实拍判断哪一档真的铺满。
#
# 用法：
#   1) 先跑 Desktop/adb-wifi-connect.sh 把无线 adb 拉起来
#   2) 三指左滑把某个应用投到仪表（保持投屏状态），再跑本脚本
#   3) HOLD=8 sh run.sh      每档停 8 秒，眼睛盯仪表；跑完直接说哪一刻铺满了
#
# 绝不把"发出去了"当"生效了"：本脚本只负责留证据，结论看实拍。
ADB="${ADB:-adb}"
HOLD="${HOLD:-7}"
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/结果-$(date +%m%d_%H%M%S)"
PKG=com.ahui.clustercast
ACT=$PKG/.MainActivity
mkdir -p "$OUT" || exit 1

"$ADB" get-state >/dev/null 2>&1 || { echo "adb 没连上。先跑 adb-wifi-connect.sh。"; exit 1; }

echo "[1] 端口与显示拓扑快照"
"$ADB" shell "netstat -tan 2>/dev/null | grep LISTEN" > "$OUT/listen.txt" 2>&1
"$ADB" shell "getprop | grep -iE 'cluster|navi|display|qnx|cabin'" > "$OUT/props.txt" 2>&1
"$ADB" shell dumpsys SurfaceFlinger --display-id > "$OUT/sf_displays.txt" 2>&1
"$ADB" shell "cmd display | head -60" > "$OUT/display_cmd.txt" 2>&1

echo "[2] 起日志（CabinLAN 转发链全量）"
"$ADB" logcat -c >/dev/null 2>&1
"$ADB" logcat -v threadtime > "$OUT/logcat.txt" 2>&1 &
LOGPID=$!
sleep 1

shot() {   # $1=用例名
  "$ADB" exec-out screencap -d 2 -p > "$OUT/d2_$1.png" 2>/dev/null
  "$ADB" exec-out screencap -d 0 -p > "$OUT/d0_$1.png" 2>/dev/null
  "$ADB" shell dumpsys SurfaceFlinger 2>/dev/null | grep -iE "Display 2|layerStack=2|^ +[0-9]+ \|" \
      > "$OUT/sf_$1.txt" 2>&1
}

mark() { echo "  >> $1  时刻 $(date +%H:%M:%S)  保持 ${HOLD}s —— 盯仪表"; }

qnx() {    # $1=用例名 $2=spec
  name=$1; spec=$2
  printf "%-16s %s  %s\n" "$name" "$(date +%H:%M:%S)" "$spec" | tee -a "$OUT/时刻表.txt"
  "$ADB" shell am start -n "$ACT" --es qnx "$spec" >/dev/null 2>&1
  sleep 1
  shot "$name"
  mark "$name"
  sleep "$HOLD"
}

echo "[3] 逐条发"
printf "用例             时刻      qnx 参数\n" | tee "$OUT/时刻表.txt"
qnx base_load     load=0
# NaviDisplayArea 枚举只有 0~4（VDValueCarLan$NaviDisplayArea 逐字读的），5 以上不存在，别往上添。
qnx a0_CLOSE_CAST   "area=0"
qnx a1_MIDDLE_1ST   "area=1"
qnx a2_LEFT_1ST     "area=2"
qnx a3_SECOND       "area=3"
qnx a4_THIRD        "area=4"
qnx a0_again        "area=0"
qnx p0    "latch=1,1,0,0"
qnx p1    "latch=1,1,1,0"
qnx p2    "latch=1,1,2,0"
qnx p3    "latch=1,1,3,0"
qnx p4    "latch=1,1,4,0"
qnx req1  "latch=1,1,0,1"
qnx rel1  "latch=0,1,0,1"
qnx nosync  "show=1,1,0,0"
qnx off   "latch=0,0,0,0"

sleep 2
kill $LOGPID 2>/dev/null
echo "[4] 抓到日志 $(wc -l < "$OUT/logcat.txt" 2>/dev/null) 行"
grep -nE "0x1004|CabinLANVDS|setMessage|NaviDisplay" "$OUT/logcat.txt" | head -80 > "$OUT/qnx相关.txt" 2>/dev/null
echo "证据在 $OUT"
echo "下一步：把 时刻表.txt 里哪一档铺满了告诉我（肉眼看的，不看截图结论）。"
