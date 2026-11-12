#!/usr/bin/env bash
# 冒烟测试：起服务（指向一个不可达的 ClickHouse）→ 跑 MCP 握手 → 停服务。
# 用法： bash tools/run_smoke_test.sh [端口]
set -uo pipefail

PORT="${1:-18000}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"

export JAVA_HOME="$HOME/.sdkman/candidates/java/17.0.12-tem"
export PATH="$JAVA_HOME/bin:$PATH"

JAR="target/spring-ai-clickhouse-mcp.jar"
if [ ! -f "$JAR" ]; then
  echo "没找到 $JAR，先跑 bin/build.sh clean package" >&2
  exit 1
fi

mkdir -p .run
LOG=".run/smoke-$PORT.log"

CLICKHOUSE_MCP_HOST=127.0.0.1 \
CLICKHOUSE_MCP_SECURE=false \
CLICKHOUSE_MCP_CONNECT_TIMEOUT_MS=3000 \
  nohup java -jar "$JAR" --server.port="$PORT" > "$LOG" 2>&1 &
PID=$!
echo "服务已启动 pid=$PID 日志=$LOG"

cleanup() {
  if kill -0 "$PID" 2>/dev/null; then
    kill -TERM "$PID" 2>/dev/null
    for _ in $(seq 1 15); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
    kill -9 "$PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

CODE=""
for i in $(seq 1 60); do
  CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://127.0.0.1:$PORT/health" || true)"
  if [ "$CODE" = "200" ] || [ "$CODE" = "503" ]; then
    echo "/health 就绪：HTTP $CODE（503 表示 ClickHouse 不可达，本测试正是要这个）"
    break
  fi
  kill -0 "$PID" 2>/dev/null || { echo "进程已退出，日志：" >&2; tail -40 "$LOG" >&2; exit 1; }
  sleep 1
done

if [ "$CODE" != "200" ] && [ "$CODE" != "503" ]; then
  echo "启动超时，日志尾部：" >&2
  tail -40 "$LOG" >&2
  exit 1
fi

python3 tools/mcp_smoke_test.py "http://127.0.0.1:$PORT"
RC=$?

echo "--- 服务日志尾部 ---"
tail -15 "$LOG"
exit $RC
