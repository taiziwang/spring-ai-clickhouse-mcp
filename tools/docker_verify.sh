#!/usr/bin/env bash
# Docker 部署验证：ClickHouse + MCP 服务都跑在容器里，然后跑完整 E2E。
#
# 前置：Docker 引擎可用；镜像已构建（docker build -t spring-ai-clickhouse-mcp:1.0.0 .）
#
# 用法：
#   bash tools/docker_verify.sh
# 可用环境变量覆盖：CH_CONTAINER / SERVER_CONTAINER / NETWORK / PORT / CH_USER / CH_PASSWORD
set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"

CH_CONTAINER="${CH_CONTAINER:-ch-mcp-test}"
SERVER_CONTAINER="${SERVER_CONTAINER:-ch-mcp-server}"
NETWORK="${NETWORK:-ch-net}"
PORT="${PORT:-18001}"
CH_USER="${CH_USER:-mcp}"
CH_PASSWORD="${CH_PASSWORD:-mcp123}"
IMAGE="${IMAGE:-spring-ai-clickhouse-mcp:1.0.0}"

echo "== 1. 准备网络与 ClickHouse 容器 =="
docker network create "$NETWORK" >/dev/null 2>&1 || true
docker network connect "$NETWORK" "$CH_CONTAINER" >/dev/null 2>&1 || true
docker start "$CH_CONTAINER" >/dev/null 2>&1 || true

for _ in $(seq 1 40); do
  if docker exec "$CH_CONTAINER" clickhouse-client --user "$CH_USER" --password "$CH_PASSWORD" \
       --query "SELECT 1" >/dev/null 2>&1; then
    echo "  ClickHouse 就绪（容器 $CH_CONTAINER）"
    break
  fi
  sleep 1
done

echo "== 2. 启动 MCP 服务容器 =="
docker rm -f "$SERVER_CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$SERVER_CONTAINER" --network "$NETWORK" -p "${PORT}:8000" \
  -e CLICKHOUSE_MCP_HOST="$CH_CONTAINER" \
  -e CLICKHOUSE_MCP_SECURE=false \
  -e CLICKHOUSE_MCP_USERNAME="$CH_USER" \
  -e CLICKHOUSE_MCP_PASSWORD="$CH_PASSWORD" \
  "$IMAGE" >/dev/null || { echo "容器启动失败" >&2; exit 1; }

echo "  等待 /health 返回 200（真实探测 ClickHouse）..."
OK=0
for i in $(seq 1 60); do
  CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://127.0.0.1:$PORT/health" || true)"
  if [ "$CODE" = "200" ]; then
    echo "  /health=200（用时 ${i}s）"
    OK=1
    break
  fi
  sleep 1
done
if [ "$OK" -ne 1 ]; then
  echo "服务未就绪，容器日志：" >&2
  docker logs --tail 40 "$SERVER_CONTAINER" >&2
  exit 1
fi

docker ps --filter "name=$SERVER_CONTAINER" --format "  {{.Names}} | {{.Status}} | {{.Ports}}"

echo "== 3. 跑端到端用例（全部 48 项）=="
python3 tools/e2e_clickhouse_test.py "http://127.0.0.1:$PORT"
RC=$?

echo "== 4. 容器日志尾部 =="
docker logs --tail 8 "$SERVER_CONTAINER" 2>&1 | sed 's/^/  /'

echo
echo "停止：docker rm -f $SERVER_CONTAINER"
exit $RC
