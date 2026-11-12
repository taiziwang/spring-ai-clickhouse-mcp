#!/usr/bin/env bash
# 后台启动 HTTP 模式：写 PID 文件 + 日志文件。
#
# 用法：
#   bin/start-http.sh
#   SERVER_PORT=8001 bin/start-http.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_common.sh"

locate_jdk
require_jar

PORT="${SERVER_PORT:-8000}"
mkdir -p "$RUNDIR"
PIDFILE="$RUNDIR/ch-mcp-$PORT.pid"
LOGFILE="$RUNDIR/ch-mcp-$PORT.log"

EXISTING="$(port_pid "$PORT")"
if [ -n "$EXISTING" ]; then
  echo "端口 $PORT 已被 PID $EXISTING 占用：" >&2
  ps -o pid=,ppid=,stat=,etime=,args= -p "$EXISTING" 2>/dev/null | cut -c1-200 >&2 || true
  STAT="$(ps -o stat= -p "$EXISTING" 2>/dev/null)"
  case "$STAT" in
    *T*) echo "  （该进程处于挂起状态 —— 多半是按了 Ctrl+Z 而不是 Ctrl+C）" >&2 ;;
  esac
  echo "先停掉它： bin/stop-http.sh $PORT" >&2
  echo "或换端口： SERVER_PORT=8001 bin/start-http.sh" >&2
  exit 1
fi

echo "使用 $JAVA_HOME"
java -version 2>&1 | head -1
echo "后台启动 clickhouse-mcp-server（内嵌 Tomcat）"
echo "  端口  : $PORT"
echo "  日志  : $LOGFILE"
echo "  PID   : $PIDFILE"

nohup java -jar "$JAR" --server.port="$PORT" "$@" > "$LOGFILE" 2>&1 &
PID=$!
echo "$PID" > "$PIDFILE"

READY=0
for _ in $(seq 1 60); do
  if curl -s -o /dev/null --max-time 2 "http://127.0.0.1:$PORT/health"; then
    READY=1
    break
  fi
  pid_alive "$PID" || break
  sleep 1
done

if [ "$READY" -eq 1 ]; then
  echo "✅ 已就绪，MCP 端点： http://localhost:$PORT/mcp"
  echo "   健康检查： http://localhost:$PORT/health"
  echo "   停止： bin/stop-http.sh $PORT"
  exit 0
fi

echo "❌ 启动失败或超时。日志尾部：" >&2
tail -30 "$LOGFILE" >&2 || true
pid_alive "$PID" && kill -TERM "$PID" 2>/dev/null || true
rm -f "$PIDFILE"
exit 1
