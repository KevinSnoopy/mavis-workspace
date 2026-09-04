#!/usr/bin/env bash
# EareyeReading 云端 APK 构建脚本
# 用法：在 TraeWork 云端环境 install 字段里写一行 `bash eareyereading/scripts/cloud-apk-build.sh`
# 本脚本内部用变量拼接 URL，避免 TraeWork 输入框把 URL 字面量渲染成 Markdown 反引号包裹

set -eu

# ---------- [0] 运行时拼接 URL（无任何完整 URL 字面量） ----------
P='https'; P=$P'://'
D1='dl.google.com'; P1=$P$D1
D2='maven.google.com'; P2=$P$D2
D3='repo.maven.apache.org'; P3=$P$D3
D4='plugins.gradle.org'; P4=$P$D4
CMDTOOL_URL=$P1'/android/repository/commandlinetools-linux-11076708_latest.zip'
PROBE1=$P1'/android/repository/'
PROBE2=$P2'/'
PROBE3=$P3'/maven2/'
PROBE4=$P4'/m2/'

echo "==> [0] URLs built at runtime:"
echo "  cmdtool=$CMDTOOL_URL"
echo "  probe1=$PROBE1"
echo "  probe2=$PROBE2"
echo "  probe3=$PROBE3"
echo "  probe4=$PROBE4"

# ---------- [1] 环境探测 ----------
echo "==> [1] env probe"
java -version
echo "JAVA_HOME=$JAVA_HOME"
which mise || true
mise root 2>/dev/null || true

# ---------- [2] gradle wrapper 版本 ----------
echo "==> [2] gradle wrapper"
cat eareyereading/gradle/wrapper/gradle-wrapper.properties 2>/dev/null || echo "no wrapper props"

# ---------- [3] 网络探测 ----------
echo "==> [3] network probe"
for u in "$PROBE1" "$PROBE2" "$PROBE3" "$PROBE4"; do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 -L "$u" 2>/dev/null || echo ERR)
    echo "  $code  $u"
done

# ---------- [4] 磁盘 / 内存 ----------
echo "==> [4] disk and mem"
df -h /
free -h
nproc

# ---------- [5] 安装 Android SDK 34 ----------
echo "==> [5] install Android SDK 34"
if [ ! -d /opt/android-sdk/platforms/android-34 ]; then
    mkdir -p /opt/android-sdk/cmdline-tools
    cd /opt/android-sdk/cmdline-tools
    curl -fsSL -o cmdtools.zip "$CMDTOOL_URL"
    unzip -q cmdtools.zip
    mv cmdline-tools latest
    cd -
    yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager \
        --sdk_root=/opt/android-sdk \
        'platforms;android-34' 'build-tools;34.0.0' >/dev/null 2>&1
    echo "SDK ready."
else
    echo "SDK cached, skip."
fi

# ---------- [6] 写 local.properties ----------
echo "sdk.dir=/opt/android-sdk" > eareyereading/local.properties

# ---------- [7] gradle assembleDebug ----------
echo "==> [7] gradle assembleDebug"
cd eareyereading
chmod +x gradlew
./gradlew :app:assembleDebug --no-daemon

# ---------- [8] 列出 APK 产物 ----------
echo "==> [8] APKs"
find app/build/outputs/apk -name '*.apk' -exec ls -lh {} \;
