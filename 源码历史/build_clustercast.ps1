param([switch]$Install)
$ErrorActionPreference = 'Stop'
# 仪表投屏 v3 构建脚本（系统手势广播版：无 root、无 Shizuku、无无障碍）
# 用法：  powershell -ExecutionPolicy Bypass -File build_clustercast.ps1          只编译打包
#        powershell -ExecutionPolicy Bypass -File build_clustercast.ps1 -Install  编译后推到车机并用 MT Manager 拉起安装
# 说明：  aapt2/javac 在含中文的目录下会失败，所以源码先同步到 %TEMP%\ccbuild 再构建，产物拷回本目录。

$SDK   = "$env:USERPROFILE\AndroidSDK"
$JDK   = "$env:USERPROFILE\.trae-cn\extensions\redhat.java-1.56.0-win32-x64\jre\21.0.12.1-win32-x86_64"
$PROJ  = $PSScriptRoot
$BT    = "$SDK\build-tools\34.0.0"
$AJ    = "$SDK\platforms\android-34\android.jar"
$SRC   = "$env:TEMP\ccbuild3"
$B     = "$SRC\build"
$env:JAVA_HOME = $JDK
$env:Path = "$JDK\bin;$env:Path"

Write-Host "=== [0/6] sync -> $SRC ==="
New-Item -ItemType Directory -Force -Path $B | Out-Null
Copy-Item "$PROJ\AndroidManifest.xml" $SRC -Force
foreach ($d in @("res", "src")) {
    if (Test-Path "$SRC\$d") { Remove-Item "$SRC\$d" -Recurse -Force }
    Copy-Item "$PROJ\$d" "$SRC\$d" -Recurse -Force
}
foreach ($d in @("$B\gen", "$B\obj", "$B\out")) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
Remove-Item "$B\obj\*", "$B\out\*" -Recurse -Force -ErrorAction SilentlyContinue

Write-Host "=== [1/6] aapt2 compile ==="
& "$BT\aapt2.exe" compile --dir "$SRC\res" -o "$B\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "=== [2/6] aapt2 link ==="
& "$BT\aapt2.exe" link -o "$B\unsigned.apk" -I "$AJ" --manifest "$SRC\AndroidManifest.xml" "$B\res.zip" --java "$B\gen" --min-sdk-version 26 --target-sdk-version 28
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "=== [3/6] javac ==="
$javas = @(Get-ChildItem -Recurse "$SRC\src" -Filter *.java | ForEach-Object FullName)
$javas += @(Get-ChildItem -Recurse "$B\gen" -Filter *.java | ForEach-Object FullName)
& "$JDK\bin\javac.exe" -g:none -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$AJ" -d "$B\obj" $javas
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "=== [4/6] d8 dex ==="
$classes = @(Get-ChildItem -Recurse "$B\obj" -Filter *.class | ForEach-Object FullName)
& "$BT\d8.bat" --min-api 26 --lib "$AJ" --output "$B\out" $classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "=== [5/6] package + align ==="
Copy-Item "$B\unsigned.apk" "$B\withdex.apk" -Force
Push-Location "$B\out"
& "$BT\aapt.exe" add -f "$B\withdex.apk" classes.dex | Out-Null
Pop-Location
& "$BT\zipalign.exe" -f -p 4 "$B\withdex.apk" "$B\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "=== [6/6] sign ==="
if (!(Test-Path "$PROJ\debug.keystore")) {
    & "$JDK\bin\keytool.exe" -genkeypair -keystore "$PROJ\debug.keystore" -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
}
$KS  = "$B\debug.keystore"
Copy-Item "$PROJ\debug.keystore" $KS -Force
$APK = "$PROJ\ClusterCast-v3.apk"
& "$BT\apksigner.bat" sign --ks $KS --ks-pass pass:android --key-pass pass:android --out "$B\v3.apk" "$B\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "sign failed" }
& "$BT\apksigner.bat" verify "$B\v3.apk"
Copy-Item "$B\v3.apk" $APK -Force

Write-Host "DONE: $APK"
if ($Install) {
    # 这台 ROM 上 adb install / adb install -r -g / pm install 全部被拦
    # （SecurityException: Restriction prevents installing，门禁在 shell uid 上，换签名方式没用）。
    # 可行通路只有一个：推到 /sdcard/Download/，让 MT Manager 拉起系统安装页，人手点一次「安装」。
    $ADB  = "$SDK\platform-tools\adb.exe"
    $name = Split-Path $APK -Leaf
    & $ADB push "$APK" "/sdcard/Download/$name"
    & $ADB shell am start -n bin.mt.plus/.OpenFileActivity `
        -d "file:///sdcard/Download/$name" -t application/vnd.android.package-archive
    Write-Host "已推送到 /sdcard/Download/$name —— 在车机上点「安装」，装完手动启动。"
}
