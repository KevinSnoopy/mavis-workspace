#!/bin/bash
# EareyeReading CI 代码质量检查脚本
# 用法: bash scripts/ci_check.sh
#
# 与 GitHub Actions 的 quality job 对齐：detekt + 单元测试，任一失败即非零退出。

set -eo pipefail

# 无论从哪里调用都定位到项目根（eareyereading/）
cd "$(dirname "$0")/.."

echo "==> 运行 Detekt 代码检查..."
./gradlew :app:detekt --no-daemon

echo ""
echo "==> 运行单元测试..."
./gradlew :app:testDebugUnitTest --no-daemon

echo ""
echo "==> 全部通过"
echo "报告位置: app/build/reports/detekt.html / app/build/reports/tests/testDebugUnitTest"
