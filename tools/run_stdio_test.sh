#!/usr/bin/env bash
# stdio 传输冒烟：向 stdin 喂一条 initialize，检查 stdout 是否是干净的 JSON-RPC。
#
# 这一步专门验「stdout 没有被日志/banner 污染」—— 一旦污染，MCP 客户端只会报解析失败，
# 症状很隐蔽。所以这里把 stdout 与 stderr 分开收集，再断言 stdout 第一行就是 JSON。
set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"

export JAVA_HOME="$HOME/.sdkman/candidates/java/17.0.12-tem"
export PATH="$JAVA_HOME/bin:$PATH"

JAR="target/spring-ai-clickhouse-mcp.jar"
[ -f "$JAR" ] || { echo "没找到 $JAR" >&2; exit 1; }

mkdir -p .run
OUT=".run/stdio-stdout.txt"
ERR=".run/stdio-stderr.txt"

printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"stdio-test","version":"1.0"}}}' \
  | CLICKHOUSE_MCP_HOST=127.0.0.1 CLICKHOUSE_MCP_SECURE=false \
    timeout 40 java -jar "$JAR" --spring.profiles.active=stdio \
    > "$OUT" 2> "$ERR"

echo "--- stdout ---"
cat "$OUT"
echo "--- stderr（尾部）---"
tail -3 "$ERR"

FIRST_LINE="$(head -1 "$OUT")"
case "$FIRST_LINE" in
  \{*)
    if grep -q '"serverInfo"' "$OUT"; then
      echo "✅ stdio 模式正常：stdout 首行即 JSON-RPC，含 serverInfo"
      exit 0
    fi
    echo "❌ stdout 是 JSON 但没有 serverInfo" >&2
    exit 1
    ;;
  *)
    echo "❌ stdout 被污染，首行不是 JSON：$FIRST_LINE" >&2
    exit 1
    ;;
esac
