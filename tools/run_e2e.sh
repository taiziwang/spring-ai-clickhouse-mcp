#!/usr/bin/env bash
# 对真实 ClickHouse 跑端到端验证：起服务 → 跑 tools/e2e_clickhouse_test.py → 停服务。
#
# 前置：ClickHouse 已在 127.0.0.1:8123 就绪，且已灌入测试数据（见 tools/seed_clickhouse.sql）。
#
# 用法：
#   bash tools/run_e2e.sh [端口] [CH主机] [CH端口] [CH用户] [CH密码]
# 默认：18000 127.0.0.1 8123 mcp mcp123
set -uo pipefail

PORT="${1:-18000}"
CH_HOST="${2:-127.0.0.1}"
CH_PORT="${3:-8123}"
CH_USER="${4:-mcp}"
CH_PASSWORD="${5:-mcp123}"

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"

export JAVA_HOME="$HOME/.sdkman/candidates/java/17.0.12-tem"
export PATH="$JAVA_HOME/bin:$PATH"

JAR="target/spring-ai-clickhouse-mcp.jar"
[ -f "$JAR" ] || { echo "没找到 $JAR，先跑 bin/build.sh clean package" >&2; exit 1; }

mkdir -p .run
LOG=".run/e2e-$PORT.log"

CLICKHOUSE_MCP_HOST="$CH_HOST" \
CLICKHOUSE_MCP_PORT="$CH_PORT" \
CLICKHOUSE_MCP_SECURE=false \
CLICKHOUSE_MCP_USERNAME="$CH_USER" \
CLICKHOUSE_MCP_PASSWORD="$CH_PASSWORD" \
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
  if [ "$CODE" = "200" ]; then
    echo "/health 就绪：HTTP 200（ClickHouse 可达），用时 ${i}s"
    break
  fi
  kill -0 "$PID" 2>/dev/null || { echo "进程已退出，日志：" >&2; tail -40 "$LOG" >&2; exit 1; }
  sleep 1
done

if [ "$CODE" != "200" ]; then
  echo "服务未能连上 ClickHouse（/health=$CODE），日志尾部：" >&2
  tail -40 "$LOG" >&2
  exit 1
fi

python3 tools/e2e_clickhouse_test.py "http://127.0.0.1:$PORT"
RC=$?

echo "--- 服务日志中的告警/错误 ---"
grep -E "WARN|ERROR" "$LOG" | tail -10 || true
exit $RC
