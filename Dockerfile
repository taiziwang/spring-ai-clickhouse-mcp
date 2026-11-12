#
# spring-ai-clickhouse-mcp —— 容器镜像
#
# 镜像里不跑 Maven：fat jar 本来就是自包含的，构建交给 bin/build.sh（本地）或 CI。
#
# 构建：
#     bin/build.sh clean package
#     docker build -t spring-ai-clickhouse-mcp:1.0.0 .
#
# 运行（HTTP 模式）：
#     docker run -d --name ch-mcp -p 8000:8000 \
#       -e CLICKHOUSE_MCP_HOST='clickhouse.internal' \
#       -e CLICKHOUSE_MCP_SECURE='false' \
#       -e CLICKHOUSE_MCP_USERNAME='readonly_user' \
#       -e CLICKHOUSE_MCP_PASSWORD='******' \
#       spring-ai-clickhouse-mcp:1.0.0
#
# 运行（stdio 模式，由 MCP 客户端拉起）：
#     docker run -i --rm spring-ai-clickhouse-mcp:1.0.0 --spring.profiles.active=stdio
#     ⚠️ 用 -i 但【不要加 -t】—— 分配 TTY 会把 \n 翻译成 \r\n，破坏 JSON-RPC 分帧。

FROM eclipse-temurin:17-jre-jammy

# curl 只给 HEALTHCHECK 用，装完立刻清 apt 缓存
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# 非 root 运行。固定 uid/gid，方便 K8s 的 runAsNonRoot / securityContext 引用。
RUN groupadd --system --gid 10001 clickhouse \
 && useradd --system --uid 10001 --gid clickhouse --home-dir /app --shell /usr/sbin/nologin clickhouse

WORKDIR /app
COPY --chown=clickhouse:clickhouse target/spring-ai-clickhouse-mcp.jar /app/app.jar

USER clickhouse

# JAVA_TOOL_OPTIONS 会被 JVM 自动拾取：
#   MaxRAMPercentage        让堆跟着容器内存 limit 走
#   ExitOnOutOfMemoryError  OOM 时直接退出，交给编排器重启
ENV SERVER_PORT=8000 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8000

# /health 会真实探测 ClickHouse 可达性（结果缓存 5s），可直接作为存活与就绪探针。
# 它有意不做认证，与 Python 版 mcp-clickhouse 的语义一致。
HEALTHCHECK --interval=30s --timeout=5s --start-period=25s --retries=3 \
    CMD curl -sS -o /dev/null --max-time 4 "http://127.0.0.1:${SERVER_PORT}/health" || exit 1

# exec 让 java 接管 PID 1，docker stop 的 SIGTERM 才能触发优雅关闭；
# 末尾 "$@" 允许 docker run 追加参数覆盖配置。
ENTRYPOINT ["sh", "-c", "exec java -jar /app/app.jar --server.port=${SERVER_PORT} \"$@\"", "--"]
