#!/bin/bash
# EareyeReading CI 代码质量检查脚本
# 用法: bash scripts/ci_check.sh

set -e

echo "🔍 运行 Detekt 代码检查..."
./gradlew detekt --no-daemon --stacktrace

echo ""
echo "📊 生成报告位置: app/build/reports/detekt.html"
echo "💡 在本地浏览器打开报告查看详情"
