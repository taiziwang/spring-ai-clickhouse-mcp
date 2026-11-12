#!/usr/bin/env bash
# stdio 模式启动：由 MCP 客户端拉起，走 stdin/stdout 通信。
#
# ⚠️ 本脚本【绝不向 stdout 输出任何东西】—— stdout 是 MCP 协议通道。
#    所有提示信息一律走 stderr，否则会污染协议、让客户端解析失败。
#
# 客户端配置示例（WorkBuddy / Claude Desktop 之类的 mcp.json）：
#   {
#     "mcpServers": {
#       "clickhouse": {
#         "command": "/home/agent/projects/spring-ai-clickhouse-mcp/bin/run-stdio.sh",
#         "env": {
#           "CLICKHOUSE_MCP_HOST": "localhost",
#           "CLICKHOUSE_MCP_PASSWORD": ""
#         }
#       }
#     }
#   }
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_common.sh"

locate_jdk
require_jar

# 提示走 stderr（不是 stdout）
echo "[run-stdio] 使用 $JAVA_HOME" >&2
echo "[run-stdio] 启动 clickhouse-mcp-server（stdio 传输）" >&2

exec java -jar "$JAR" --spring.profiles.active=stdio "$@"
