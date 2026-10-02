#!/bin/bash
# v18 build: merged EVCam sources + androidx/material AARs, merged res, multi-dex
# 双平台：本机 Git Bash 用固定路径；CI/Linux 用 ANDROID_HOME + PATH 里的 JDK17
set -e
case "$(uname -s)" in
  Linux*|Darwin*) CPSEP=":"; D8=d8; SIGNER=apksigner ;;
  *) CPSEP=";"
     if [ -d /c/Users/Administrator/jdk-17/jdk-17.0.2 ]; then
       export JAVA_HOME=C:/Users/Administrator/jdk-17/jdk-17.0.2
       export PATH="/c/Users/Administrator/jdk-17/jdk-17.0.2/bin:$PATH"
     fi
     [ -n "$ANDROID_HOME" ] || ANDROID_HOME=/c/Users/Administrator/AndroidSDK ;;
esac
BT="$ANDROID_HOME/build-tools/34.0.0"
BT36="$ANDROID_HOME/build-tools/36.0.0"
PLAT="$ANDROID_HOME/platforms/android-34/android.jar"
cd "$(dirname "$0")/.."
ROOT=$PWD
B=build2
rm -rf $B
mkdir -p $B/flat $B/aar $B/obj $B/dex $B/gen $B/pack

# 1. AAR keep-list, low->high res merge priority (later wins)
KEEP="work-runtime room-ktx room-runtime sqlite-framework sqlite core-runtime tracing startup-runtime loader interpolator versionedparcelable
vectordrawable vectordrawable-animated exifinterface cardview drawerlayout cursoradapter
customview customview-poolingcontainer coordinatorlayout recyclerview viewpager2 transition dynamicanimation
graphics-shapes emoji2 emoji2-views-helper fragment navigationevent constraintlayout material
appcompat appcompat-resources core core-ktx glide
activity savedstate lifecycle-livedata lifecycle-livedata-core lifecycle-runtime
lifecycle-viewmodel lifecycle-viewmodel-savedstate lifecycle-process"
> $B/aars.txt
for k in $KEEP; do
  for f in libs/${k}-*.aar; do
    [ -e "$f" ] || continue
    b=$(basename "$f" .aar)
    [ "${b/-[0-9]*/}" = "$k" ] || continue
    echo "$b"
  done | sort -V | tail -1 >> $B/aars.txt
done
# jars: latest version per artifact name
ls libs/*.jar | sed 's|libs/||; s|\.jar$||' | while read b; do echo "${b/-[0-9]*/}|$b"; done \
  | sort -t'|' -k1,1 -k2,2V | awk -F'|' '{a[$1]=$2} END{for(k in a) print a[k]}' | sort > $B/jars.txt
echo "AARs: $(wc -l < $B/aars.txt)  JARs: $(wc -l < $B/jars.txt)"

# 2. extract each AAR
while read a; do
  d=$B/aar/$a
  mkdir -p $d
  (cd $d && unzip -oq "$ROOT/libs/$a.aar" > /dev/null) || { echo "UNZIP FAILED: $a"; exit 1; }
done < $B/aars.txt

# 3. merge all res (AARs in priority order, ours last) then compile once
RESARGS=""
while read a; do
  [ -d $B/aar/$a/res ] && [ -n "$(ls -A $B/aar/$a/res 2>/dev/null)" ] && RESARGS="$RESARGS $B/aar/$a/res"
done < $B/aars.txt
perl tools/merge_res.pl $RESARGS res --out $B/res_merged
"$BT/aapt2" compile --dir $B/res_merged -o $B/flat/all.zip

# 4. link
EXTRA=(com.kooo.evcam androidx.appcompat androidx.appcompat.resources androidx.core androidx.fragment androidx.recyclerview
  androidx.transition androidx.drawerlayout androidx.coordinatorlayout androidx.cardview
  androidx.customview androidx.customview.poolingcontainer androidx.viewpager2 androidx.emoji2 androidx.emoji2.views androidx.emoji2.text
  androidx.vectordrawable androidx.interpolator androidx.constraintlayout androidx.exifinterface
  androidx.work androidx.activity androidx.navigationevent androidx.lifecycle androidx.lifecycle.runtime androidx.lifecycle.viewmodel
  androidx.savedstate androidx.emoji2.viewsintegration androidx.loader androidx.startup androidx.tracing
  androidx.versionedparcelable androidx.dynamicanimation androidx.graphics
  com.google.android.material com.bumptech.glide)
EXTRA_ARGS=(); for p in "${EXTRA[@]}"; do EXTRA_ARGS+=(--extra-packages "$p"); done
"$BT/aapt2" link -o $B/base.apk -I "$PLAT" --manifest AndroidManifest.xml -A assets \
  --min-sdk-version 27 --target-sdk-version 28 --version-code 75 --version-name 18.47 \
  --java $B/gen --auto-add-overlay "${EXTRA_ARGS[@]}" $B/flat/all.zip
echo "LINK OK"
find $B/gen -name 'R.java' | head -5

# 5. javac
CP="$PLAT$CPSEP$(ls $B/aar/*/classes.jar | tr '\n' "$CPSEP")$(sed 's|^|libs/|; s|$|.jar|' $B/jars.txt | tr '\n' "$CPSEP")"
find src -name '*.java' > $B/srcs.txt
find $B/gen -name 'R.java' >> $B/srcs.txt
javac -encoding UTF-8 -source 11 -target 11 -Xlint:-options -nowarn \
  -cp "$CP" -d $B/obj @$B/srcs.txt 2> $B/javac.log \
  || { echo JAVAC FAILED; tail -80 $B/javac.log; exit 1; }
echo "JAVAC OK"

# 6. d8 -> multi-dex (d8 rejects a directory arg, so jar our classes first)
(cd $B/obj && jar cf ../obj.jar .)
D8IN="$B/obj.jar $(ls $B/aar/*/classes.jar | tr '\n' ' ') $(sed 's|^|libs/|; s|$|.jar|' $B/jars.txt | tr '\n' ' ')"
"$BT36/$D8" --release --min-api 27 --lib "$PLAT" --output $B/dex $D8IN > $B/d8.log 2>&1 \
  || { echo D8 FAILED; tail -40 $B/d8.log; exit 1; }
ls -la $B/dex
echo "D8 OK"

# 7. package dex + jniLib
cp $B/dex/*.dex $B/pack/
mkdir -p $B/pack/lib/arm64-v8a
cp jniLibs/arm64-v8a/*.so $B/pack/lib/arm64-v8a/
(cd $B/pack && for d in classes*.dex; do "$BT/aapt" add ../base.apk "$d" > /dev/null; done
 "$BT/aapt" add ../base.apk lib/arm64-v8a/libvhal_decoder.so > /dev/null)

# 8. align + sign
mkdir -p out
"$BT/zipalign" -f 4 $B/base.apk $B/aligned.apk
"$BT/$SIGNER" sign --ks debug.keystore --ks-pass pass:android \
  --out out/ClusterCast.apk $B/aligned.apk
"$BT/aapt" dump badging out/ClusterCast.apk | head -2
jar tf out/ClusterCast.apk | grep -E '^classes.*dex|^lib/' 
echo "BUILD OK"
