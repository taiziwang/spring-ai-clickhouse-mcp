#!/usr/bin/env bash
# 前台启动 HTTP 模式。Ctrl+C 停止。
#
# ⚠️ 如果 Ctrl+C 没反应，先确认你按的不是 Ctrl+Z：
#     Ctrl+Z 发的是 SIGTSTP —— 【挂起】而不是【终止】。被挂起的进程
#     仍然占着端口，而且不再运行，所以对 Ctrl+C 的 SIGINT 毫无反应。
#     收尾：bin/stop-http.sh；或者先 fg 把它恢复到前台，再按 Ctrl+C。
#
# 想后台长期跑，用 bin/start-http.sh + bin/stop-http.sh。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_common.sh"

locate_jdk
require_jar

PORT="${SERVER_PORT:-8000}"

EXISTING="$(port_pid "$PORT")"
if [ -n "$EXISTING" ]; then
  echo "端口 $PORT 已被 PID $EXISTING 占用：" >&2
  ps -o pid=,ppid=,stat=,etime=,args= -p "$EXISTING" 2>/dev/null | cut -c1-200 >&2 || true
  STAT="$(ps -o stat= -p "$EXISTING" 2>/dev/null || true)"
  case "$STAT" in
    *T*) echo "  （该进程处于挂起状态 —— 多半是按了 Ctrl+Z 而不是 Ctrl+C）" >&2 ;;
  esac
  echo "先停掉它： bin/stop-http.sh $PORT" >&2
  echo "或换端口： SERVER_PORT=8001 bin/run.sh" >&2
  exit 1
fi

echo "使用 $JAVA_HOME"
java -version 2>&1 | head -1
echo "启动 clickhouse-mcp-server（内嵌 Tomcat），MCP 端点： http://localhost:${PORT}/mcp"
echo "健康检查： http://localhost:${PORT}/health"
echo "停止：Ctrl+C（前台）"
exec java -jar "$JAR" --server.port="$PORT" "$@"
