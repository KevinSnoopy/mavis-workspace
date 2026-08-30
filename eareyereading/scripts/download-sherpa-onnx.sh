#!/usr/bin/env bash
#
# 下载并放置 sherpa-onnx 预编译 Android 原生库 + Kotlin 源码。
#
# sherpa-onnx 不发布 Maven/JitPack AAR。官方集成方式：
#   1. 从 GitHub Release 下载 sherpa-onnx-v<ver>-android.tar.bz2（含 .so）
#   2. 从仓库 android/SherpaOnnxTts/app/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt 拷贝源码
#
# 用法：
#   ./scripts/download-sherpa-onnx.sh            # 用默认版本
#   ./scripts/download-sherpa-onnx.sh 1.10.30    # 指定版本
#
set -euo pipefail

SHERPA_ONNX_VERSION="${1:-1.10.30}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI_DIR="$REPO_ROOT/app/src/main/jniLibs"
SRC_DIR="$REPO_ROOT/app/src/main/java/com/k2fsa/sherpa/onnx"

# 项目 abiFilters 只保留这两个 ABI
ABIS=("arm64-v8a" "armeabi-v7a")

echo "==> sherpa-onnx v${SHERPA_ONNX_VERSION}"
TARBALL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_ONNX_VERSION}/sherpa-onnx-v${SHERPA_ONNX_VERSION}-android.tar.bz2"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

echo "==> 下载 $TARBALL_URL"
# --fail：HTTP 404/5xx 直接失败，不把错误页当 tarball 存下来
curl -fsSL -o "$TMP_DIR/sherpa.tar.bz2" "$TARBALL_URL"
mkdir -p "$TMP_DIR/extracted"
tar xjf "$TMP_DIR/sherpa.tar.bz2" -C "$TMP_DIR/extracted"

echo "==> 校验 tarball 内容"
# 先在暂存区校验所有 ABI 齐全，再动仓库里的既有 .so；
# 旧实现先删 $JNI_DIR，tarball 缺内容时仓库直接失去所有原生库
STAGE_DIR="$TMP_DIR/staged"
for abi in "${ABIS[@]}"; do
  src="$TMP_DIR/extracted/jniLibs/$abi"
  if [[ ! -d "$src" ]]; then
    echo "错误：tarball 中缺少 ABI $abi"; exit 1
  fi
  mkdir -p "$STAGE_DIR/$abi"
  cp "$src"/libsherpa-onnx-jni.so "$STAGE_DIR/$abi/"
  cp "$src"/libonnxruntime.so     "$STAGE_DIR/$abi/"
done

echo "==> 拷贝 Tts.kt 到暂存区并校验"
TTS_KT_URL="https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v${SHERPA_ONNX_VERSION}/android/SherpaOnnxTts/app/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt"
curl -fsSL -o "$TMP_DIR/Tts.kt" "$TTS_KT_URL"
# 版本不存在时 raw.githubusercontent 返回 "404: Not Found" 文本；
# --fail 已拦截绝大多数情况，这里再验内容，防止把错误页提交进源码树
grep -q "^package com.k2fsa.sherpa.onnx" "$TMP_DIR/Tts.kt" || {
  echo "错误：Tts.kt 内容异常（不是预期的 Kotlin 源码）"; exit 1;
}

echo "==> 校验通过，放置 .so 到 $JNI_DIR"
rm -rf "$JNI_DIR"
mkdir -p "$JNI_DIR"
for abi in "${ABIS[@]}"; do
  mkdir -p "$JNI_DIR/$abi"
  cp "$STAGE_DIR/$abi"/*.so "$JNI_DIR/$abi/"
  echo "  $abi: $(ls -1 "$JNI_DIR/$abi" | tr '\n' ' ')"
done

echo "==> 安装 Tts.kt 到 $SRC_DIR"
mkdir -p "$SRC_DIR"
cp "$TMP_DIR/Tts.kt" "$SRC_DIR/Tts.kt"
echo "  Tts.kt: $(wc -l < "$SRC_DIR/Tts.kt") 行"

echo "==> 完成。请更新 app/build.gradle.kts 注释中的版本号，并提交 .so 与 Tts.kt。"
