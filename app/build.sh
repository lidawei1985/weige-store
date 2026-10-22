#!/bin/bash
# 伟哥应用商店 TV 客户端 —— 无 gradle 打包
# 依赖：JDK 17+、Android SDK build-tools、platforms/android-34
set -e

cd "$(dirname "$0")"
APP_NAME="伟哥应用商店"
VERSION="1.0"

SDK="${ANDROID_SDK:-C:/Users/sbqqq/Android}"
BT="$SDK/build-tools/36.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
KS="${KEYSTORE:-$HOME/.android/debug.keystore}"

w() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

for f in "$BT/aapt2.exe" "$BT/d8.bat" "$BT/zipalign.exe" "$BT/apksigner.bat" "$PLATFORM"; do
  [ -e "$f" ] || { echo "缺少: $f"; exit 1; }
done
[ -e "$KS" ] || { echo "缺少签名文件: $KS"; exit 1; }

echo "==> 1/7 清理"
rm -rf build
mkdir -p build/compiled build/gen build/obj build/dex build/stage

echo "==> 2/7 编译资源"
"$BT/aapt2.exe" compile --dir res -o build/compiled/res.flata

echo "==> 3/7 链接资源"
"$BT/aapt2.exe" link build/compiled/res.flata \
  -I "$(w "$PLATFORM")" \
  --manifest AndroidManifest.xml \
  --java build/gen \
  -A assets \
  -o build/stage/base.apk

echo "==> 4/7 编译 Java"
find src build/gen -name "*.java" > build/sources.txt
javac --release 17 -encoding UTF-8 -nowarn -d build/obj \
  -cp "$(w "$PLATFORM")" @build/sources.txt 2>&1 | grep -v "^Note:" || true

echo "==> 5/7 dex"
find build/obj -name "*.class" > build/classes.txt
"$BT/d8.bat" --lib "$(w "$PLATFORM")" --min-api 21 --output build/dex \
  $(cat build/classes.txt | tr '\n' ' ') > build/d8.log 2>&1 || {
    echo "d8 失败："; cat build/d8.log; exit 1; }

echo "==> 6/7 合并 dex"
python - <<'PY'
import zipfile, os, shutil
base = 'build/stage/base.apk'
out = 'build/stage/unsigned.apk'
with zipfile.ZipFile(base) as zin, zipfile.ZipFile(out, 'w') as zout:
    for it in zin.infolist():
        zout.writestr(it, zin.read(it.filename), compress_type=it.compress_type)
    zout.write('build/dex/classes.dex', 'classes.dex', compress_type=zipfile.ZIP_STORED)
print('  base.apk -> unsigned.apk ok')
PY

echo "==> 7/7 对齐 + 签名"
"$BT/zipalign.exe" -p -f 4 build/stage/unsigned.apk build/stage/aligned.apk
"$BT/apksigner.bat" sign \
  --ks "$(w "$KS")" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android \
  --out "${APP_NAME}-v${VERSION}.apk" build/stage/aligned.apk

echo
echo "==> 校验"
"$BT/aapt2.exe" dump badging "${APP_NAME}-v${VERSION}.apk" | grep -E "^package|launchable-activity|leanback|sdkVersion|application-label" | head -8
"$BT/apksigner.bat" verify "${APP_NAME}-v${VERSION}.apk" && echo "签名 OK"
ls -lh "${APP_NAME}-v${VERSION}.apk"
