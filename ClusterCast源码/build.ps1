$ErrorActionPreference = 'Stop'

function Check-Last($name) {
    if ($LASTEXITCODE -ne 0) {
        throw "$name failed with exit $LASTEXITCODE"
    }
}

$root = (Resolve-Path '.').Path
$sdk = if ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} elseif ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} else {
    'C:\Users\Administrator\AndroidSDK'
}

function Get-LatestAndroidDir($parent, $prefix) {
    if (!(Test-Path -LiteralPath $parent)) {
        throw "Android SDK directory not found: $parent"
    }
    $dirs = Get-ChildItem -LiteralPath $parent -Directory |
        Where-Object { $_.Name -like "$prefix*" } |
        Sort-Object {
            $versionText = $_.Name.Substring($prefix.Length)
            $versionText = $versionText -replace '[^\d\.].*$', ''
            try { [version]$versionText } catch { [version]'0.0.0' }
        } -Descending
    if (!$dirs) {
        throw "No Android SDK component found in $parent"
    }
    $dirs[0].FullName
}

$buildTools = if ($env:ANDROID_BUILD_TOOLS_VERSION) {
    Join-Path (Join-Path $sdk 'build-tools') $env:ANDROID_BUILD_TOOLS_VERSION
} else {
    Get-LatestAndroidDir (Join-Path $sdk 'build-tools') ''
}

$platformName = if ($env:ANDROID_PLATFORM) {
    $env:ANDROID_PLATFORM
} elseif ($env:ANDROID_PLATFORM_VERSION) {
    "android-$env:ANDROID_PLATFORM_VERSION"
} else {
    $null
}
$platformDir = if ($platformName) {
    Join-Path (Join-Path $sdk 'platforms') $platformName
} else {
    Get-LatestAndroidDir (Join-Path $sdk 'platforms') 'android-'
}
$androidJar = Join-Path $platformDir 'android.jar'

$isWindowsHost = $PSVersionTable.Platform -eq 'Win32NT' -or $env:OS -eq 'Windows_NT'
$exeSuffix = if ($isWindowsHost) { '.exe' } else { '' }
$scriptSuffix = if ($isWindowsHost) { '.bat' } else { '' }

$aapt2 = Join-Path $buildTools "aapt2$exeSuffix"
$aapt = Join-Path $buildTools "aapt$exeSuffix"
$d8 = Join-Path $buildTools "d8$scriptSuffix"
$zipalign = Join-Path $buildTools "zipalign$exeSuffix"
$apksigner = Join-Path $buildTools "apksigner$scriptSuffix"
$javac = Join-Path $env:JAVA_HOME "bin/javac$exeSuffix"
$keytool = Join-Path $env:JAVA_HOME "bin/keytool$exeSuffix"

foreach ($tool in @($aapt2, $aapt, $d8, $zipalign, $apksigner, $androidJar, $javac)) {
    if (!(Test-Path -LiteralPath $tool)) {
        throw "Required build input not found: $tool"
    }
}

$genDir = Join-Path $root 'gen'
$classesDir = Join-Path $root 'classes'
$outDir = Join-Path $root 'out'
$dexDir = Join-Path $outDir 'dex'
foreach ($d in @($genDir, $classesDir, $outDir, $dexDir)) {
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

Push-Location $root
try {
    Write-Host "[1/6] aapt2 compile"
    & $aapt2 compile --dir res -o (Join-Path $outDir 'res.zip')
    Check-Last 'aapt2 compile'

    Write-Host "[2/6] aapt2 link"
    # targetSdk 必须停在 28：Android 10 起系统禁止 targetSdk>=29 的应用往
    # 「无系统装饰」的虚拟屏启动 Activity，28 是第三方 app 能用虚拟屏投应用的最后门槛
    $linkArgs = @('link', '-o', (Join-Path $outDir 'app.base.apk'), '-I', $androidJar,
        '--manifest', 'AndroidManifest.xml', '--java', $genDir,
        '--min-sdk-version', '27', '--target-sdk-version', '28',
        '--version-code', '27', '--version-name', '17.0')
    if (Test-Path -LiteralPath 'assets') { $linkArgs += @('-A', 'assets') }
    $linkArgs += (Join-Path $outDir 'res.zip')
    & $aapt2 @linkArgs
    Check-Last 'aapt2 link'

    Write-Host "[3/6] javac"
    if (Test-Path -LiteralPath $classesDir) { Remove-Item -Recurse -Force $classesDir }
    New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
    $srcs = @(Get-ChildItem -Recurse -File src -Filter *.java | ForEach-Object { $_.FullName.Substring($root.Length + 1) })
    $srcs += @(Get-ChildItem -Recurse -File $genDir -Filter *.java | ForEach-Object { $_.FullName.Substring($root.Length + 1) })
    & $javac -encoding UTF-8 -source 11 -target 11 -Xlint:-options -classpath $androidJar -d $classesDir $srcs
    Check-Last 'javac'

    Write-Host "[4/6] d8"
    # class 数量多时命令行会超 Windows 长度限制，先收成一个 jar 再喂 d8
    $clsJar = Join-Path $outDir 'classes.jar'
    if (Test-Path -LiteralPath $clsJar) { Remove-Item -Force $clsJar }
    Push-Location $classesDir
    & (Join-Path $env:JAVA_HOME "bin/jar$exeSuffix") cf $clsJar .
    Pop-Location
    Check-Last 'jar cf'
    & $d8 --min-api 27 --lib $androidJar --output $dexDir $clsJar
    Check-Last 'd8'

    Write-Host "[5/6] package dex + zipalign"
    Push-Location $dexDir
    & $aapt add (Join-Path $outDir 'app.base.apk') 'classes.dex'
    Check-Last 'aapt add'
    Pop-Location
    & $zipalign -f 4 (Join-Path $outDir 'app.base.apk') (Join-Path $outDir 'app.aligned.apk')
    Check-Last 'zipalign'

    Write-Host "[6/6] apksigner"
    $ks = Join-Path $root 'debug.keystore'
    if (!(Test-Path -LiteralPath $ks)) {
        & $keytool -genkeypair -keystore $ks -storepass android -keypass android `
            -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
            -dname 'CN=Android Debug,O=Android,C=US'
        Check-Last 'keytool'
    }
    & $apksigner sign --ks $ks --ks-pass pass:android --key-pass pass:android --out (Join-Path $outDir 'ClusterCast.apk') (Join-Path $outDir 'app.aligned.apk')
    Check-Last 'apksigner sign'

    Write-Host "DONE: out/ClusterCast.apk"
}
finally {
    Pop-Location
}
