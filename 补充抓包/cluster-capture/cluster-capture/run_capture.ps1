$ErrorActionPreference = "Continue"

$OUT = "capture_" + (Get-Date -Format "yyyyMMdd_HHmmss")
$DevScript = "/data/local/tmp/cluster_start.sh"
$DevTmp = "/data/local/tmp"
New-Item -ItemType Directory -Path $OUT -Force | Out-Null

Write-Host "=== 仪表投屏协议一键抓包 ==="

# 1. Check ADB (修复了正则判断问题)
Write-Host "检查 ADB 设备..."
$devices = (& adb devices 2>&1 | Out-String)
if ($devices -notmatch "\bdevice\b") {
  Write-Host "错误: 没有检测到 ADB 设备，请先连接车机并开启 USB 调试。"
  exit 1
}
Write-Host "[1/6] ADB 设备已连接"

# 2. Check root
$HasRoot = 0
try {
  & adb shell "su -c 'id'" 2>$null | Out-Null
  $HasRoot = 1
} catch {}
Write-Host "[2/6] root 权限: $(if($HasRoot -eq 1){'有'}else{'无'})"

function DCmd([string]$cmd) {
  if ($HasRoot -eq 1) {
    & adb shell "su -c '$cmd'" 2>$null | Out-String
  } else {
    & adb shell $cmd 2>$null | Out-String
  }
}

# 3. Collect system info
Write-Host "[3/6] 采集系统信息..."
DCmd "getprop" | Out-File "$OUT\getprop.txt" -Encoding utf8
$allPkgs = DCmd "pm list packages"
$allPkgs | Out-File "$OUT\packages_all.txt" -Encoding utf8
$matches = ($allPkgs -split "`n") | Where-Object { $_ -match "(?i)amap|autonavi|navi|gaode|cluster|instrument|hmi|ivi|oem" }
$matches | Out-File "$OUT\packages.txt" -Encoding utf8
DCmd "dumpsys display" | Out-File "$OUT\display.txt" -Encoding utf8
DCmd "ps -A" | Out-File "$OUT\processes.txt" -Encoding utf8
Write-Host "  getprop / packages / display / processes 已保存"

# Find running package
$PKG = ""
foreach ($line in $matches) {
  $pkg = ($line -split ':',2)[1].Trim()
  if (-not $pkg) { continue }
  $pid = (DCmd "pidof $pkg").Trim()
  if ($pid) { $PKG = $pkg; break }
}
if ($PKG) { Write-Host "  运行中的导航包: $PKG" } else { Write-Host "  警告: 没有运行中的导航包，strace 将跳过" }

# 4. Start device-side capture
Write-Host "[4/6] 启动设备端捕获 (logcat + tcpdump + strace)..."
$devScriptContent = @'
#!/system/bin/sh
DevTmp="/data/local/tmp"
Pkg="$1"

logcat -c
logcat -v time > "$DevTmp/cluster_capture.log" 2>&1 &
echo $! > "$DevTmp/cluster_logpid"

if command -v tcpdump >/dev/null 2>&1; then
  tcpdump -i lo -s 0 -w "$DevTmp/cluster_capture.cap" 2>/dev/null &
  echo $! > "$DevTmp/cluster_tcappid"
fi

if [ -n "$Pkg" ] && command -v strace >/dev/null 2>&1; then
  PidNow=$(pidof "$Pkg" 2>/dev/null | awk '{print $1}')
  if [ -n "$PidNow" ]; then
    strace -f -e trace=open,openat,connect,bind,listen,sendto,recvfrom -p "$PidNow" -o "$DevTmp/cluster_strace.txt" 2>/dev/null &
    echo $! > "$DevTmp/cluster_strpid"
  fi
fi
echo "ok"
'@
[IO.File]::WriteAllText("$OUT\cluster_start.sh", $devScriptContent, [System.Text.Encoding]::ASCII)

& adb push "$OUT\cluster_start.sh" "$DevScript" 2>$null | Out-Null
if ($HasRoot -eq 1) {
  & adb shell "su -c 'sh $DevScript $PKG'" 2>$null | Out-Null
} else {
  & adb shell "sh $DevScript $PKG" 2>$null | Out-Null
}
Write-Host "  捕获已启动"

# 5. User action
Write-Host ""
Write-Host "  现在请依次操作："
Write-Host "    1. 车机进入高德车机版"
Write-Host "    2. 点击『投屏到仪表』"
Write-Host "    3. 保持 20 秒"
Write-Host "    4. 取消投屏"
Write-Host "    5. 再等 10 秒"
Write-Host ""
Read-Host "  完成后按回车继续..."

# 6. Stop and pull (修复了 $PID 变量冲突)
Write-Host "[5/6] 停止捕获..."
foreach ($pidfile in @("cluster_logpid","cluster_tcappid","cluster_strpid")) {
  # 修复：把 $pid 改名为 $procId
  $procId = (& adb shell "cat $DevTmp/$pidfile 2>/dev/null" 2>$null | Out-String).Trim()
  if ($procId) {
    $killCmd = "kill $procId 2>/dev/null"
    if ($HasRoot -eq 1) {
      & adb shell "su -c '$killCmd'" 2>$null | Out-Null
    } else {
      & adb shell $killCmd 2>$null | Out-Null
    }
  }
}
Start-Sleep -Seconds 3

$grepPattern = "amap|autonavi|navi|gaode|cluster|instrument|display|mirror|projection|window|surface|binder|aidl|socket|bind|connect"
$grepCmd = "grep -iE '$grepPattern' $DevTmp/cluster_capture.log > $DevTmp/cluster_capture_filtered.log 2>/dev/null"

if ($HasRoot -eq 1) {
  & adb shell "su -c ""$grepCmd""" 2>$null | Out-Null
} else {
  & adb shell $grepCmd 2>$null | Out-Null
}

Write-Host "  拉取文件..."
& adb pull "$DevTmp/cluster_capture.log" "$OUT\logcat_cluster.log" 2>$null | Out-Null
& adb pull "$DevTmp/cluster_capture_filtered.log" "$OUT\logcat_cluster_filtered.log" 2>$null | Out-Null
& adb pull "$DevTmp/cluster_strace.txt" "$OUT\strace_amap.txt" 2>$null | Out-Null
& adb pull "$DevTmp/cluster_capture.cap" "$OUT\tcpdump_local.cap" 2>$null | Out-Null

# Summary
$summary = @()
$summary += "capture_time: " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
$summary += "target_package: " + $(if($PKG){$PKG}else{"unknown"})
$summary += "root: " + $(if($HasRoot -eq 1){"yes"}else{"no"})
$summary += ""
$summary += "files:"
$existing = Get-ChildItem "$OUT" -File | Where-Object { $_.Name -ne "cluster_start.sh" } | Select-Object -ExpandProperty Name
$summary += $existing | ForEach-Object { "  $_" }
$summary -join "`n" | Out-File "$OUT\summary.txt" -Encoding utf8

# 7. Package
Write-Host "[6/6] 打包..."
Push-Location $OUT
try {
  & Compress-Archive -Path "." -DestinationPath "..\$OUT.zip" -Force 2>$null | Out-Null
} catch {
  Write-Host "  Compress-Archive 失败，尝试 7z..."
  & 7z a "..\$OUT.zip" -r . 2>$null | Out-Null
}
Pop-Location
Remove-Item "$OUT" -Recurse -Force -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "完成: $OUT.zip"
Write-Host "请把这个 zip 文件发给我，我继续分析。"