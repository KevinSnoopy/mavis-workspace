#!/usr/bin/env bash
# EareyeReading 云端 APK 构建脚本
# 用法：
#   阶段 A（探测，~30-60s，含 Gradle 下载）: bash eareyereading/scripts/cloud-apk-build.sh --probe-only
#   阶段 B（完整构建，SDK+Gradle 已就绪）:   bash eareyereading/scripts/cloud-apk-build.sh
# 本脚本内部用变量拼接 URL，避免 TraeWork 输入框把 URL 字面量渲染成 Markdown 反引号包裹

set -eu

PROBE_ONLY=0
if [ "${1:-}" = "--probe-only" ]; then
    PROBE_ONLY=1
    echo "==> [mode] PROBE-ONLY（跑 [0]-[4] + Gradle 下载，不装 SDK、不构建）"
fi

# ---------- [0] 运行时拼接 URL（无任何完整 URL 字面量） ----------
P='https'; P=$P'://'
D1='dl.google.com'; P1=$P$D1
D2='maven.google.com'; P2=$P$D2
D3='repo.maven.apache.org'; P3=$P$D3
D4='plugins.gradle.org'; P4=$P$D4
D5='services.gradle.org'; P5=$P$D5
CMDTOOL_URL=$P1'/android/repository/commandlinetools-linux-11076708_latest.zip'
GRADLE_VER='8.2'
GRADLE_DIST=$P5'/distributions/gradle-'$GRADLE_VER'-bin.zip'
GRADLE_HOME=/opt/gradle-$GRADLE_VER
PROBE1=$P1'/android/repository/'
PROBE2=$P2'/'
PROBE3=$P3'/maven2/'
PROBE4=$P4'/m2/'
PROBE5=$P5'/distributions/'

echo "==> [0] URLs built at runtime:"
echo "  cmdtool=$CMDTOOL_URL"
echo "  gradle_dist=$GRADLE_DIST"
echo "  probe1=$PROBE1"
echo "  probe2=$PROBE2"
echo "  probe3=$PROBE3"
echo "  probe4=$PROBE4"
echo "  probe5=$PROBE5"

# ---------- [1] 环境探测 ----------
echo "==> [1] env probe"
java -version
echo "JAVA_HOME=$JAVA_HOME"
which mise || true
mise root 2>/dev/null || true

# ---------- [2] gradle wrapper 版本（读项目配置） ----------
echo "==> [2] gradle wrapper (project config)"
cat eareyereading/gradle/wrapper/gradle-wrapper.properties 2>/dev/null || echo "no wrapper props"

# ---------- [3] 网络探测（含 services.gradle.org） ----------
echo "==> [3] network probe"
for u in "$PROBE1" "$PROBE2" "$PROBE3" "$PROBE4" "$PROBE5"; do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 -L "$u" 2>/dev/null || echo ERR)
    echo "  $code  $u"
done

# ---------- [4] 磁盘 / 内存 ----------
echo "==> [4] disk and mem"
df -h /
free -h
nproc

# ---------- [5] 下载并安装 Gradle 8.2（探测阶段就做，避免阶段 B 超时） ----------
echo "==> [5] install Gradle $GRADLE_VER"
if [ -x "$GRADLE_HOME/bin/gradle" ]; then
    echo "Gradle $GRADLE_VER cached at $GRADLE_HOME, skip."
else
    mkdir -p /opt/gradle-dist
    cd /opt/gradle-dist
    curl -fsSL -o gradle.zip "$GRADLE_DIST"
    unzip -q gradle.zip
    rm -f gradle.zip
    echo "Gradle $GRADLE_VER installed at $GRADLE_HOME"
fi
export PATH="$GRADLE_HOME/bin:$PATH"
gradle --version

if [ "$PROBE_ONLY" -eq 1 ]; then
    echo "==> [probe done] 环境 + Gradle 就绪。阶段 B 装 SDK 并构建: bash eareyereading/scripts/cloud-apk-build.sh"
    exit 0
fi

# ---------- [6] 安装 Android SDK 34 ----------
echo "==> [6] install Android SDK 34"
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

# ---------- [7] 写 local.properties ----------
echo "sdk.dir=/opt/android-sdk" > eareyereading/local.properties

# ---------- [8] gradle assembleDebug（用已安装的 gradle，绕开 wrapper 下载） ----------
echo "==> [8] gradle assembleDebug"
cd eareyereading
chmod +x gradlew
# 优先用 /opt/gradle-8.2/bin/gradle，避免 wrapper 再次下载
if [ -x "$GRADLE_HOME/bin/gradle" ]; then
    "$GRADLE_HOME/bin/gradle" :app:assembleDebug --no-daemon
else
    ./gradlew :app:assembleDebug --no-daemon
fi

# ---------- [9] 列出 APK 产物 ----------
echo "==> [9] APKs"
find app/build/outputs/apk -name '*.apk' -exec ls -lh {} \;
