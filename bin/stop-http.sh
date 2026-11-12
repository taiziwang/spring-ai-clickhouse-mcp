#!/usr/bin/env bash
# 停止后台 HTTP 进程。用法： bin/stop-http.sh [端口]   （默认 8000）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_common.sh"

PORT="${1:-8000}"
PIDFILE="$RUNDIR/ch-mcp-$PORT.pid"

PID=""
if [ -f "$PIDFILE" ]; then
  PID="$(cat "$PIDFILE")"
fi
if [ -z "$PID" ] || ! pid_alive "$PID"; then
  # PID 文件失效时按端口找
  PID="$(port_pid "$PORT")"
fi

if [ -z "$PID" ] || ! pid_alive "$PID"; then
  echo "没有找到端口 $PORT 上的 clickhouse-mcp-server 进程。"
  rm -f "$PIDFILE"
  exit 0
fi

echo "停止 PID $PID ..."
stop_pid "$PID"
rm -f "$PIDFILE"
echo "已停止。"
