#!/usr/bin/env bash
# 构建入口。
#
# 为什么要这个脚本：MCP 客户端 / CI / systemd 都以「非交互进程」方式拉起命令，
# 而 SDKMAN 只在交互式 shell 里初始化 —— 那些场景下 PATH 上根本没有 java / mvn。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_common.sh"

cd "$PROJECT_DIR"
locate_jdk

MVN="$HOME/.sdkman/candidates/maven/3.9.9/bin/mvn"
if [ ! -x "$MVN" ]; then
  MVN="$(command -v mvn || true)"
fi
if [ -z "$MVN" ]; then
  echo "没找到 mvn。请安装 Maven 3.9+ 或设置好 PATH。" >&2
  exit 1
fi

echo "JAVA_HOME = $JAVA_HOME"
java -version 2>&1 | head -1
echo "MVN       = $MVN"

exec "$MVN" "$@"
