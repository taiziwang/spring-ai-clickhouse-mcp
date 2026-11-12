# spring-ai-clickhouse-mcp

把 **ClickHouse 的查询能力以 MCP 工具暴露给多个 Agent** 的服务。
这是 [ClickHouse/mcp-clickhouse](https://github.com/ClickHouse/mcp-clickhouse)（Python / FastMCP）的
**Spring AI 等价改写**，工程形态与同仓的 `spring-ai-hive-mcp` 一致。

- 技术栈：Spring Boot 4.1 + Spring AI 2.0（MCP Server，Streamable HTTP / stdio）+ ClickHouse 官方 JDBC（走 HTTP 接口）
- 部署形态：单个可执行 fat jar（内嵌 Tomcat），或容器镜像（非 root，uid 10001）
- JDK 基线：17

**验证状态**：对 `clickhouse/clickhouse-server:25.8`（Docker）跑通 48 项端到端用例，
服务本身也在容器内验证通过（见「验证」一节）。

## 1. 与 Python 版的对照

| Python 版（mcp-clickhouse） | 本项目 |
|---|---|
| `run_query` | `run_query`（只读校验 + 限额 + 错误分类） |
| `list_databases` | `list_databases` |
| `list_tables`（like / not_like / 分页 / 列详情） | `list_tables`（同名参数 + `page_size`/`page_token` + `include_detailed_columns`） |
| `/health`（无认证，K8s 探针） | `/health`（真实探测 ClickHouse，结果缓存 5s） |
| `CLICKHOUSE_HOST/PORT/SECURE/VERIFY/USER/PASSWORD/DATABASE` | `clickhouse-mcp.*`（环境变量 `CLICKHOUSE_MCP_*` 宽松绑定） |
| `CLICKHOUSE_ALLOW_WRITE_ACCESS` / `CLICKHOUSE_ALLOW_DROP` | `allow-write-access` / `allow-drop`（三级守卫） |
| 只读时服务端 `readonly=1` 强制 | 同：JDBC URL 带 `custom_http_params=readonly=1` |
| 超 JS 安全范围的整数、DECIMAL 以字符串返回 | 同（`JdbcValues`），但安全范围内的整数返回数值 |
| `CLICKHOUSE_MCP_QUERY_TIMEOUT`（默认 30s） | `query-timeout-ms`（默认 30000） |
| chDB（嵌入式引擎） | **不移植**：chDB 是 Python 生态能力，Java 侧无对应物 |
| OAuth / OIDC 认证（FastMCP auth） | **未移植**：按内网共享账号网关设计；需要认证请前置网关 |
| —（Python 版没有） | 运维诊断工具 `get_pool_status` / `get_server_status`，可按开关关闭 |

## 2. 工具清单

### 业务工具（始终注册）

| 工具 | 说明 |
|---|---|
| `list_databases` | 列出当前账号可见的数据库 |
| `list_tables` | 列出指定库的表及元数据（引擎/注释/建表语句/依赖），支持 `like`/`not_like` 过滤、分页、`include_detailed_columns` 列详情 |
| `run_query` | 执行单条 SQL。默认只读：`SELECT / WITH / SHOW / DESCRIBE / EXPLAIN / EXISTS`；服务端 `readonly=1` 强制 |

`list_tables` 参数（与 Python 版同名同义）：
`database`（必填）、`like`、`not_like`、`include_detailed_columns`、`page_size`、`page_token`。
返回 `{ database, tables: [{ database, name, engine, comment, createTableQuery, dependencies, columns }], nextPageToken, truncated }`，
`nextPageToken=null` 表示末页。

### 运维诊断工具（`clickhouse-mcp.diagnostics.enabled=false` 可整组关闭）

| 工具 | 说明 |
|---|---|
| `get_pool_status` | 连接池实时状态（OK / FULL / SATURATED / NOT_INITIALIZED），`probe=true` 会实际借连接执行 `SELECT 1` |
| `get_server_status` | 传输模式、JVM 内存、生效限额、访问级别（READONLY/WRITE/WRITE_WITH_DROP）、SQL 执行统计 |

## 3. 快速开始（本地 jar）

```bash
bin/build.sh clean package        # 自动定位 SDKMAN 的 JDK 17 与 Maven

export CLICKHOUSE_MCP_HOST=localhost
export CLICKHOUSE_MCP_SECURE=false      # 内网 HTTP 8123；HTTPS 8443 设 true
export CLICKHOUSE_MCP_USERNAME=default
export CLICKHOUSE_MCP_PASSWORD=

bin/run.sh                # 前台 HTTP（默认端口 8000）
bin/start-http.sh         # 后台 + PID/日志到 .run/
bin/stop-http.sh
```

- MCP 端点：`http://localhost:8000/mcp`（Streamable HTTP，协议 2025-06-18）
- 健康检查：`http://localhost:8000/health`（ClickHouse 可达 200，否则 503）

### stdio 模式

```json
{
  "mcpServers": {
    "clickhouse": {
      "command": "/home/agent/projects/spring-ai-clickhouse-mcp/bin/run-stdio.sh",
      "env": {
        "CLICKHOUSE_MCP_HOST": "localhost",
        "CLICKHOUSE_MCP_SECURE": "false",
        "CLICKHOUSE_MCP_PASSWORD": ""
      }
    }
  }
}
```

## 4. Docker 部署

```bash
bin/build.sh clean package
docker build -t spring-ai-clickhouse-mcp:1.0.0 .
docker run -d --name ch-mcp -p 8000:8000 \
  -e CLICKHOUSE_MCP_HOST='clickhouse.internal' \
  -e CLICKHOUSE_MCP_SECURE='false' \
  -e CLICKHOUSE_MCP_USERNAME='readonly_user' \
  -e CLICKHOUSE_MCP_PASSWORD='******' \
  spring-ai-clickhouse-mcp:1.0.0
```

镜像内非 root（uid 10001），`HEALTHCHECK` 直接用 `/health` —— 它真实探测 ClickHouse，
可同时作为 K8s 的存活与就绪探针（ClickHouse 不可达时返回 503，这正是就绪探针想要的语义）。

stdio 模式在容器里跑要 `-i` 但**不要** `-t`（分配 TTY 会把 `\n` 翻成 `\r\n`，破坏 JSON-RPC 分帧）：

```bash
docker run -i --rm spring-ai-clickhouse-mcp:1.0.0 --spring.profiles.active=stdio
```

## 5. Kubernetes 部署（NodePort）

清单在 `k8s/`，命名空间 `ckmcp`，所有资源带 `app: ckmcp` 标签：

```bash
kubectl apply -k k8s/                       # Namespace/ConfigMap/Secret/ClickHouse/MCP/NodePort 一次装齐
kubectl -n ckmcp rollout status deploy/ckmcp

curl http://localhost:30801/health          # → OK
# MCP 端点： http://localhost:30801/mcp
```

| 资源 | 说明 |
|---|---|
| `Deployment ckmcp` | 镜像 `spring-ai-clickhouse-mcp:1.0.0`，1 副本，非 root + 只读根文件系统 |
| `Service ckmcp` | **NodePort 30801 → 8000** |
| `Deployment clickhouse` + `Service clickhouse` | 配套 ClickHouse 25.8（ClusterIP 8123），可整份删掉改连外部集群 |

探针分工是刻意的：`readinessProbe` 用 `/health`（ClickHouse 不可达就摘流量），
`livenessProbe` 只用 `tcpSocket` —— 否则下游抖动会把 Pod 反复重启。
细节见 [`k8s/README.md`](k8s/README.md)。

已在 Docker Desktop K8s（v1.36.1）实测：两个 Deployment 均 1/1 Running，
NodePort 上跑通全部 48 项 E2E；删除 MCP Pod 后自动重建、1 秒内恢复 200。

## 6. 验证

三个脚本，都已在 ClickHouse 25.8 上实测通过：

```bash
# ① 单元测试（24 项：SQL 守卫三级行为 + 错误分类规则）
bin/build.sh clean package

# ② 端到端（48 项）：需先有可连的 ClickHouse
docker run -d --name ch-mcp-test -p 8123:8123 --ulimit nofile=262144:262144 \
  -e CLICKHOUSE_USER=mcp -e CLICKHOUSE_PASSWORD=mcp123 \
  -e CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1 clickhouse/clickhouse-server:25.8
docker exec -i ch-mcp-test clickhouse-client --user mcp --password mcp123 \
  --multiquery < tools/seed_clickhouse.sql
bash tools/run_e2e.sh 18000 127.0.0.1 8123 mcp mcp123

# ③ 纯 Docker 部署验证（ClickHouse 与服务都在容器里，走 docker 网络互连）
bash tools/docker_verify.sh

# ④ stdio 传输冒烟（断言 stdout 首行就是 JSON-RPC，没被日志污染）
bash tools/run_stdio_test.sh
```

E2E 覆盖点：工具清单与参数名契约、库/表枚举、`like`/`not_like` 过滤、分页翻页、
列详情（类型/注释）、聚合与 `DESCRIBE`/`EXPLAIN`/`SHOW CREATE`、中文与引号转义、
UInt64 超 JS 安全范围转字符串、Decimal 保精度、服务端只读强制、
INSERT/DROP 拦截、语法错/表不存在/列不存在/多语句/非法标识符的分类、
结果预算截断、连接池与运行状态诊断。

## 7. 访问级别（三级开关）

| 级别 | 配置 | 放行的语句首词 |
|---|---|---|
| 只读（默认） | `allow-write-access: false` | SELECT / WITH / SHOW / DESCRIBE / DESC / EXPLAIN / EXISTS；服务端 `readonly=1` 强制 |
| 写 | `allow-write-access: true` | 以上 + INSERT / CREATE |
| 危险 | `allow-write-access: true` + `allow-drop: true` | 以上 + DROP / TRUNCATE / ALTER / RENAME / ATTACH / DETACH / OPTIMIZE / KILL / DELETE |

任何级别都**永远禁止** `GRANT / REVOKE / SYSTEM / SET / USE / INTO OUTFILE / INFILE`
（授权与运维命令不属于取数网关职责；`SET`/`USE` 会污染池化连接的会话状态）。
`SELECT ... SETTINGS x=1` 是语句级设置，**放行**。

⚠️ 客户端守卫（`SqlGuard`）只是体验优化，**真正的安全边界是 ClickHouse 侧的用户授权**
与只读模式下的 `readonly=1`。生产环境请给本服务配只读账号。

## 8. 限额与截断策略

| 配置 | 默认 | 说明 |
|---|---|---|
| `query-timeout-ms` | 30000 | 单查询墙钟超时，看门狗线程到点 `Statement.cancel()` |
| `max-rows` / `max-bytes` | 5000 / 4MB | 结果双预算，任一超限即截断 |
| `on-truncate` | ERROR | `ERROR`：超预算直接报 `QueryLimitExceeded`；`PARTIAL`：返回部分数据并置 `truncated=true` |
| `metadata-max-rows` | 2000 | 元数据工具行数预算（也是 `list_tables` 单页上限） |
| `pool-size` | 5 | HikariCP 池上限 |

## 9. 错误协议

失败时 `isError=true`，内容是紧凑 JSON：

```json
{"code":"QueryTimeout","message":"...","hint":"查询超出时间预算。请加 LIMIT ...","retryable":true}
```

| code | 含义 | retryable |
|---|---|---|
| `InvalidArgument` | SQL 语法/表名/列名问题，或多语句、非法标识符、拼写错误（Code 60/62/81/47/386） | ✅ 改 SQL 重试 |
| `AccessDenied` | 无权限 / 认证失败 / readonly 拦截（Code 497/516/164） | ❌ 改 SQL 无用，找管理员 |
| `QueryTimeout` | 超时已取消（Code 160） | ✅ 加 LIMIT/聚合 |
| `QueryLimitExceeded` | 结果超预算 | ✅ 聚合或分批 |
| `DatabaseUnavailable` | 连不上 ClickHouse / 池借不到连接 | ✅ 原样重试 |
| `InternalError` | 兜底 | ❌ 找管理员 |

## 10. 踩过的坑（改代码前请先读）

1. **ClickHouse JDBC 0.10 的 URL 参数名**：是 `connection_timeout` / `socket_timeout`，
   不是 v1 时代的 `connect_timeout`。写错会被拒成
   `ClientMisconfigurationException: Unknown and unmapped config properties`，
   而外层只抛 `SQLException("Failed to create connection")` —— 极易误判成网络问题。
2. **Spring AI 2.0 的 `@ToolParam` 没有 name**，JSON Schema 属性名直接取 Java 反射参数名，
   `@JsonProperty` 对它**不生效**（实测）。所以 `list_tables` 的参数刻意写成
   `not_like` / `page_size` 这种 snake_case，以保持与 Python 版的契约一致。
3. **`.dockerignore` 不能写 `target/`**：会把 Dockerfile 要 COPY 的 fat jar 一起排除，
   报错却落在 `COPY` 行（`failed to compute cache key: ...jar: not found`）。
   正确写法是 `target/*` + `!target/spring-ai-clickhouse-mcp.jar`。
4. **UInt64 / UInt16 的类型映射不统一**：`count()` 走 NUMERIC、`n * 2` 走 INTEGER。
   数值分支必须显式处理 `Number`，否则会被 `toString()` 成 `"20"`，Agent 拿去比较就错了。
   只有超出 ±(2^53-1) 的整数才转字符串。
5. **只读是否真的生效**要用 `SELECT 1 SETTINGS max_threads=1` 验证：它是合法只读语句，
   但 `readonly=1` 下改任何设置都会被拒（Code 164）。用写语句验证是无效的 ——
   客户端守卫会先拦住，看不出服务端有没有生效。
6. **驱动对确定性错误也会重试 3 次并打完整栈**，噪音很大。已把
   `com.clickhouse.client.api.Client` 日志级别压到 ERROR，错误由执行器分类后记录一行。

## 11. 项目结构

```
src/main/java/com/example/chmcp/
├── ClickHouseMcpApplication.java   入口
├── config/                         配置面 + 数据源（URL 构造/只读强制）+ 工具注册
├── sql/SqlGuard.java               三级语句守卫（只读/写/危险）
├── query/                          查询执行器（预算 + 看门狗 + 统计）
├── error/                          分类异常 + ClickHouse 错误码分类器
├── tool/                           业务工具 + 运维诊断工具 + 结果记录
├── util/JdbcValues.java            JDBC 值 → JSON 安全对象（大整数/DECIMAL/复杂类型）
└── health/HealthController.java    /health（HTTP 模式，无认证）
bin/                                构建 / 前台 / 后台 / stdio 启动脚本
k8s/                                Kubernetes 清单（namespace ckmcp，NodePort 30801）+ 部署说明
tools/                              seed SQL、E2E、Docker 验证、stdio 冒烟、JDBC 探针
```
