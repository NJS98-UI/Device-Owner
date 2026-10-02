param([switch]$Install)
$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }
# 仪表投屏 v4.x（Kotlin 源码）Windows 离线构建，对应同目录 rebuild.sh 的 PowerShell 版。
# 用法：  powershell -ExecutionPolicy Bypass -File build.ps1          只编译打包
#        powershell -ExecutionPolicy Bypass -File build.ps1 -Install  编译后推到车机并用 MT Manager 拉起安装
# 说明：  aapt2 在含中文的目录下会失败，所以源码先同步到 %TEMP%\ccbuild4 再构建，产物拷回本目录。
#        没有 kotlinc 可执行文件，用 gradle 发行包里的 kotlin-compiler-embeddable 直接跑 K2JVMCompiler。

$SDK   = "$env:USERPROFILE\AndroidSDK"
$JDK   = "$env:USERPROFILE\.trae-cn\extensions\redhat.java-1.56.0-win32-x64\jre\21.0.12.1-win32-x86_64"
$GLIB  = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.5-bin\5t9huq95ubn472n8rpzujfbqh\gradle-8.5\lib"
$PROJ  = $PSScriptRoot
$SRC   = "$PROJ\src"
$BT    = "$SDK\build-tools\34.0.0"
$AJ    = "$SDK\platforms\android-34\android.jar"
$KC    = "$GLIB\kotlin-compiler-embeddable-1.9.20.jar"
$SL    = "$GLIB\kotlin-stdlib-1.9.20.jar"
# 编译器自己的启动 classpath：embeddable 包不打包 stdlib/trove，缺一个就 NoClassDefFoundError
$KCP   = "$KC;$SL;$GLIB\trove4j-1.0.20200330.jar;$GLIB\annotations-24.0.1.jar;" +
         "$GLIB\kotlin-reflect-1.9.20.jar;$GLIB\kotlin-script-runtime-1.9.20.jar;" +
         "$GLIB\kotlin-daemon-embeddable-1.9.20.jar"
$B     = "$env:TEMP\ccbuild4\build"
$JAVA  = "$JDK\bin\java.exe"
$env:JAVA_HOME = $JDK
$env:Path = "$JDK\bin;$env:Path"

foreach ($f in @($KC, $SL, $AJ, "$BT\aapt2.exe", "$BT\lib\d8.jar", "$SRC\AndroidManifest.xml")) {
    if (!(Test-Path $f)) { throw "缺少构件：$f" }
}

$VER = (Select-String -Path "$SRC\AndroidManifest.xml" -Pattern 'android:versionName="([0-9.]+)"').Matches[0].Groups[1].Value
$APK = "$PROJ\ClusterCast-v$VER.apk"

Write-Host "=== [0/7] sync -> $env:TEMP\ccbuild4 (version $VER) ==="
New-Item -ItemType Directory -Force -Path $B | Out-Null
Copy-Item "$SRC\AndroidManifest.xml" "$env:TEMP\ccbuild4" -Force
foreach ($d in @("res", "src")) {
    if (Test-Path "$env:TEMP\ccbuild4\$d") { Remove-Item "$env:TEMP\ccbuild4\$d" -Recurse -Force }
    Copy-Item "$SRC\$d" "$env:TEMP\ccbuild4\$d" -Recurse -Force
}
foreach ($d in @("$B\gen", "$B\obj", "$B\r8dex", "$B\final")) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
Remove-Item "$B\gen\*", "$B\obj\*", "$B\r8dex\*", "$B\final\*", "$B\*.apk", "$B\*.zip", "$B\*.jar", "$B\res.zip" -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item "$SRC\keep.pro" "$B\keep.pro" -Force

Write-Host "=== [1/7] aapt2 compile ==="
& "$BT\aapt2.exe" compile --dir "$env:TEMP\ccbuild4\res" -o "$B\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "=== [2/7] aapt2 link ==="
& "$BT\aapt2.exe" link -o "$B\unsigned.apk" -I "$AJ" --manifest "$env:TEMP\ccbuild4\AndroidManifest.xml" "$B\res.zip" --java "$B\gen" --min-sdk-version 26 --target-sdk-version 28
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "=== [3/7] kotlinc ==="
$kts = @(Get-ChildItem -Recurse "$env:TEMP\ccbuild4\src" -Filter *.kt | ForEach-Object { '"' + $_.FullName + '"' })
# 参数逐个拼成一个数组：& 直接调 java 时 PowerShell 收不到它的报错（重定向文件是空的），
# 只有 Start-Process 的 -Redirect* 能拿到，所以这步单独走进程重定向。
$ka = New-Object System.Collections.Generic.List[string]
$ka.Add("-Dfile.encoding=UTF-8"); $ka.Add("-cp"); $ka.Add($KCP)
$ka.Add("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
$ka.Add("-nowarn"); $ka.Add("-no-stdlib"); $ka.Add("-no-reflect")
$ka.Add("-jvm-target"); $ka.Add("1.8")
$ka.Add("-cp"); $ka.Add("$AJ;$SL")
$ka.Add("-d"); $ka.Add($B + "\obj")
foreach ($k in $kts) { $ka.Add($k) }
$kp = Start-Process -FilePath $JAVA -ArgumentList $ka -NoNewWindow -Wait -PassThru `
    -RedirectStandardOutput "$B\kcout.txt" -RedirectStandardError "$B\kcerr.txt"
if ($kp.ExitCode -ne 0) {
    Write-Host (Get-Content "$B\kcout.txt" -Raw)
    Write-Host (Get-Content "$B\kcerr.txt" -Raw)
    throw "kotlinc failed"
}

Write-Host "=== [4/7] jar ==="
& "$JDK\bin\jar.exe" cf "$B\app.jar" -C "$B\obj" .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Write-Host "=== [5/7] R8（kotlin-stdlib 必须当程序输入传进去）==="
# 关键：$SL 放在位置参数里，不能放 --classpath。
# 否则 R8 把 kotlin.Unit / Function0 / DefaultConstructorMarker 当外部库剔掉，
# dex 里只有引用没有类，一启动就 NoClassDefFoundError 闪退。
$ra = New-Object System.Collections.Generic.List[string]
$ra.Add("-cp"); $ra.Add("$BT\lib\d8.jar")
$ra.Add("com.android.tools.r8.R8"); $ra.Add("--release")
$ra.Add("--min-api"); $ra.Add("26"); $ra.Add("--lib"); $ra.Add($AJ)
$ra.Add("--pg-conf"); $ra.Add("$B\keep.pro")
$ra.Add("--output"); $ra.Add("$B\out2.zip")
$ra.Add("--classpath"); $ra.Add($AJ)
# 下面两个是程序输入，顺序和内容别动
$ra.Add($B + "\app.jar"); $ra.Add($SL)
$rp = Start-Process -FilePath $JAVA -ArgumentList $ra -NoNewWindow -Wait -PassThru `
    -RedirectStandardOutput "$B\r8out.txt" -RedirectStandardError "$B\r8err.txt"
if ($rp.ExitCode -ne 0) {
    Write-Host (Get-Content "$B\r8out.txt" -Raw)
    Write-Host (Get-Content "$B\r8err.txt" -Raw)
    throw "R8 failed"
}
Expand-Archive -Path "$B\out2.zip" -DestinationPath "$B\r8dex" -Force
if (!(Test-Path "$B\r8dex\classes.dex")) { throw "R8 没产出 classes.dex" }

Write-Host "=== [6/7] dex 自检（kotlin 运行时在不在、四个组件在不在）==="
$dump = & "$BT\dexdump.exe" -f "$B\r8dex\classes.dex" 2>&1 | Out-String
$defined = [regex]::Matches($dump, "Class descriptor\s+:\s+'(L[^;]+;)'") | ForEach-Object { $_.Groups[1].Value }
$nkt = @($defined | Where-Object { $_ -like 'Lkotlin/*' }).Count
if ($nkt -le 100) { throw "kotlin 运行时被剔掉了（只有 $nkt 个 kotlin 类）" }
foreach ($c in [regex]::Matches((Get-Content "$SRC\AndroidManifest.xml" -Raw), 'android:name="\.(\w+)"')) {
    $need = 'Lcom/ahui/clustercast/' + $c.Groups[1].Value + ';'
    if ($defined -notcontains $need) { throw "dex 里缺组件：$need" }
}
Write-Host "DEX CHECK OK  classes=$(@($defined).Count) kotlin=$nkt"

Write-Host "=== [7/7] 打包 + 对齐 + 签名 ==="
Copy-Item "$B\unsigned.apk" "$B\final\app.apk" -Force
Copy-Item "$B\r8dex\classes.dex" "$B\final\classes.dex" -Force
Push-Location "$B\final"
& "$BT\aapt.exe" add -f app.apk classes.dex | Out-Null
$rc = $LASTEXITCODE
Pop-Location
if ($rc -ne 0) { throw "aapt add classes.dex failed" }
& "$BT\zipalign.exe" -f -p 4 "$B\final\app.apk" "$B\final\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }
& "$BT\apksigner.bat" sign --ks "$SRC\debug.keystore" --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --out "$B\final\signed.apk" "$B\final\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "sign failed" }
& "$BT\apksigner.bat" verify "$B\final\signed.apk"
if ($LASTEXITCODE -ne 0) { throw "verify failed" }
Copy-Item "$B\final\signed.apk" $APK -Force

Write-Host "DONE: $APK"
if ($Install) {
    # 这台 ROM 的 adb install / pm install 全被拦（SecurityException: Restriction prevents installing），
    # 唯一通路：推到 /sdcard/Download/，让 MT Manager 拉起系统安装页，人手点一次「安装」。
    $ADB  = "$SDK\platform-tools\adb.exe"
    $name = Split-Path $APK -Leaf
    & $ADB push "$APK" "/sdcard/Download/$name"
    & $ADB shell am start -n bin.mt.plus/.OpenFileActivity `
        -d "file:///sdcard/Download/$name" -t application/vnd.android.package-archive
    Write-Host "已推送到 /sdcard/Download/$name —— 在车机上点「安装」，装完手动启动。"
    Write-Host "装完记得授权一次（重启不失效）："
    Write-Host "  adb shell pm grant com.ahui.clustercast android.permission.WRITE_SECURE_SETTINGS"
}
