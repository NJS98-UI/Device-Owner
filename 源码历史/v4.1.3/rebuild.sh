#!/bin/sh
# ClusterCast v4.x（Kotlin）离线构建：需要 kotlinc 2.0.x、android-34 platform、build-tools 34.0.0
set -e
KC=${KC:-/data/kotlinc-dl/kotlinc/bin/kotlinc}
BT=${BT:-/data/sdk/build-tools/34.0.0}
AJ=${AJ:-/data/sdk/platforms/android-34/android.jar}
SL=${SL:-/data/kotlinc-dl/kotlinc/lib/kotlin-stdlib.jar}
VER=$(sed -n 's/.*android:versionName="\([0-9.]*\)".*/\1/p' src/AndroidManifest.xml | head -1)
APK=ClusterCast-v$VER.apk
rm -rf b && mkdir -p b/gen b/obj && cp src/keep.pro b/keep.pro
$BT/aapt2 compile --dir src/res -o b/res.zip
$BT/aapt2 link -o b/unsigned.apk -I $AJ --manifest src/AndroidManifest.xml b/res.zip --java b/gen --min-sdk-version 26 --target-sdk-version 28
$KC -nowarn -cp "$AJ:$SL" -d b/obj src/src/com/ahui/clustercast/*.kt
(cd b/obj && jar cf ../app.jar .)
# 关键：kotlin-stdlib 必须作为「程序输入」传进去（位置参数），不能放 --classpath，
# 否则 R8 会把 kotlin.Unit / Function0 / DefaultConstructorMarker 当外部库剔掉，
# dex 里只有引用没有类，一启动就 NoClassDefFoundError 闪退。
java -cp $BT/lib/d8.jar com.android.tools.r8.R8 --release --min-api 26 --lib $AJ \
  --pg-conf b/keep.pro --output b/out2.zip --classpath $AJ b/app.jar $SL
rm -rf b/r8dex && mkdir b/r8dex && (cd b/r8dex && unzip -q ../out2.zip)
mkdir -p b/final && cp b/unsigned.apk b/final/app.apk && cp b/r8dex/classes.dex b/final/
(cd b/final && $BT/aapt add -f app.apk classes.dex > /dev/null)
$BT/zipalign -f -p 4 b/final/app.apk b/final/aligned.apk
$BT/apksigner sign --ks src/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out $APK b/final/aligned.apk
# 交付前自检：dex 里 kotlin 运行时必须在，且不能有未解析的内部类
python3 - <<'PYCHK'
import re, subprocess, sys
BT = __import__('os').environ.get('BT', '/data/sdk/build-tools/34.0.0')
D = 'b/r8dex/classes.dex'
out = subprocess.run([BT + '/dexdump', '-f', D], capture_output=True, text=True).stdout
defined = set(re.findall(r"Class descriptor\s+:\s+'(L[^;]+;)'", out))
raw = open(D, 'rb').read()
refs = {m.decode('ascii', 'ignore') for m in re.findall(rb'L([a-zA-Z0-9_/$]+\.[a-zA-Z0-9/_$]+);', raw)}
plat = ('Ljava/', 'Landroid/', 'Landroidx/', 'Ldalvik/', 'Llibcore/', 'Lsun/',
        'Lcom/android/internal/', 'Lorg/apache/', 'Lorg/json/', 'Lorg/w3c/',
        'Lorg/xml/', 'Lorg/xmlpull/', 'Lorg/jetbrains/')
miss = sorted(d for d in refs if not d.startswith(plat) and d not in defined)
kt = len([d for d in defined if d.startswith('Lkotlin')])
assert not miss, 'MISSING CLASSES: %s' % miss
assert kt > 100, 'kotlin runtime missing (%d)' % kt
for c in re.findall(r'android:name="\.(\w+)"', open('src/AndroidManifest.xml').read()):
    assert 'Lcom/ahui/clustercast/' + c + ';' in defined, 'component absent: ' + c
print('DEX CHECK OK  classes=%d kotlin=%d' % (len(defined), kt))
PYCHK
echo BUILD_OK
