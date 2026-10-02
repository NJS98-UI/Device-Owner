#!/usr/bin/env bash
# ClusterCast v4.x（Kotlin）在 Linux 上的构建脚本，与 Windows 的 build.ps1 一一对应：
# 同一套 aapt2 / kotlinc / R8 / zipalign / apksigner 参数，同样把 kotlin-stdlib 当 R8 的「程序输入」。
# 供 .github/workflows/build-apk.yml 调用，本地有 SDK 时也能直接跑。
# 依赖：JAVA_HOME、$BT（build-tools 34.0.0）、$AJ（android-34/android.jar）、$KC_HOME（kotlin-compiler 1.9.20）
set -euo pipefail

: "${BT:?需要 build-tools 目录，如 /usr/local/lib/android/sdk/build-tools/34.0.0}"
: "${AJ:?需要 android.jar，如 .../platforms/android-34/android.jar}"
: "${KC_HOME:?需要 kotlin-compiler 解包目录（里面有 bin/kotlinc）}"
ROOT=$(cd "$(dirname "$0")" && pwd)
SRC="$ROOT/src"
SL="$KC_HOME/lib/kotlin-stdlib.jar"
WORK=$(mktemp -d)
B="$WORK/b"
trap 'rm -rf "$WORK"' EXIT

# 与 Windows 同样的理由：源码先同步到纯 ASCII 路径再构建，避免路径编码问题。
SYNC="$WORK/src"
mkdir -p "$B/gen" "$B/obj" "$B/r8dex" "$B/final" "$SYNC"
cp "$SRC/AndroidManifest.xml" "$SYNC/"
cp -r "$SRC/res" "$SRC/src" "$SYNC/"
cp "$SRC/keep.pro" "$B/keep.pro"

VER=$(sed -n 's/.*android:versionName="\([0-9.]*\)".*/\1/p' "$SRC/AndroidManifest.xml" | head -1)
echo "=== version $VER ==="

echo "=== [1/7] aapt2 compile ==="
"$BT/aapt2" compile --dir "$SYNC/res" -o "$B/res.zip"

echo "=== [2/7] aapt2 link ==="
"$BT/aapt2" link -o "$B/unsigned.apk" -I "$AJ" --manifest "$SYNC/AndroidManifest.xml" \
  "$B/res.zip" --java "$B/gen" --min-sdk-version 26 --target-sdk-version 28

echo "=== [3/7] kotlinc ==="
# 不需要编译 aapt2 生成的 R.java：全项目只用到 android.R.*，自家资源都是代码里建的。
find "$SYNC/src" -name '*.kt' > "$B/kts.txt"
# shellcheck disable=SC2046
"$KC_HOME/bin/kotlinc" -nowarn -no-stdlib -no-reflect -jvm-target 1.8 \
  -cp "$AJ:$SL" -d "$B/obj" $(cat "$B/kts.txt") 2> "$B/kterr.txt" || {
  cat "$B/kterr.txt"; exit 1
}
grep -v '^warning:' "$B/kterr.txt" >&2 || true

echo "=== [4/7] jar ==="
jar cf "$B/app.jar" -C "$B/obj" .

echo "=== [5/7] R8（kotlin-stdlib 必须作为程序输入，不能放 --classpath）==="
java -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 --release --min-api 26 --lib "$AJ" \
  --pg-conf "$B/keep.pro" --output "$B/out2.zip" --classpath "$AJ" "$B/app.jar" "$SL" 2> "$B/r8err.txt" || {
  cat "$B/r8err.txt"; exit 1
}
(cd "$B/r8dex" && unzip -q ../out2.zip)
test -s "$B/r8dex/classes.dex"

echo "=== [6/7] dex 自检 ==="
# 和 build.ps1 同样的两条硬校验：kotlin 运行时必须还在包里；manifest 声明的组件必须都能在 dex 里找到。
DUMP=$("$BT/dexdump" -f "$B/r8dex/classes.dex")
DEFINED=$(printf '%s\n' "$DUMP" | sed -n "s/.*Class descriptor.*'\(L[^;]*;\)'.*/\1/p" | sort -u)
NC=$(printf '%s\n' "$DEFINED" | wc -l)
NKT=$(printf '%s\n' "$DEFINED" | grep -c '^Lkotlin/' || true)
echo "classes=$NC kotlin=$NKT"
[ "$NKT" -gt 100 ] || { echo "kotlin 运行时被 R8 剔掉了"; exit 1; }
COMPS=$(grep -oE 'android:name="\.[A-Za-z0-9_]+"' "$SRC/AndroidManifest.xml" | sed 's/.*"\.\(.*\)"/\1/')
[ -n "$COMPS" ] || { echo "自检失败：从 manifest 里一个组件都没解析出来，等于没校验"; exit 1; }
for c in $COMPS; do
  printf '%s\n' "$DEFINED" | grep -qx "Lcom/ahui/clustercast/$c;" || { echo "dex 里缺组件：$c"; exit 1; }
done
echo "组件自检：$(printf '%s\n' "$COMPS" | tr '\n' ' ')"
echo "DEX CHECK OK"

echo "=== [7/7] 打包 + 对齐 + 签名 ==="
cp "$B/unsigned.apk" "$B/final/app.apk"
cp "$B/r8dex/classes.dex" "$B/final/"
(cd "$B/final" && "$BT/aapt" add -f app.apk classes.dex > /dev/null)
"$BT/zipalign" -f -p 4 "$B/final/app.apk" "$B/final/aligned.apk"
OUT="$ROOT/ClusterCast-v$VER.apk"
"$BT/apksigner" sign --ks "$SRC/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out "$OUT" "$B/final/aligned.apk"
"$BT/apksigner" verify "$OUT"
ls -l "$OUT"
echo "BUILD_OK $OUT"
