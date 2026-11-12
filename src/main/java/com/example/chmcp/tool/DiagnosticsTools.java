package com.example.chmcp.tool;

import com.example.chmcp.config.ClickHouseProperties;
import com.example.chmcp.error.ClickHouseErrorClassifier;
import com.example.chmcp.error.ClickHouseMcpException;
import com.example.chmcp.query.ClickHouseQueryExecutor;
import com.example.chmcp.query.QueryStats;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 运维诊断工具：连接池状态 + 服务运行状态。
 *
 * <h2>为什么单独放一个类</h2>
 * 这两个工具面向的是<b>维护方</b>，不是取数的 Agent：
 * <ol>
 *   <li>可以用 {@code clickhouse-mcp.diagnostics.enabled=false} 一键把整组关掉，
 *       不影响业务工具；</li>
 *   <li>业务工具清单保持干净，评审时一眼能看出哪些是给 Agent 的、哪些是给运维的。</li>
 * </ol>
 *
 * <h2>为什么默认开启</h2>
 * 排障时最忌讳「要先改配置重启才能看」。而且 {@code get_server_status} 对 Agent 自己
 * 也有用 —— 拿到 {@code DatabaseUnavailable} 后调一次，就能区分
 * 「网关连接池打满了，该退避重试」和「ClickHouse 本身不可达」。
 */
@Component
@ConditionalOnProperty(name = "clickhouse-mcp.diagnostics.enabled", havingValue = "true", matchIfMissing = true)
public class DiagnosticsTools {

    private final DataSource dataSource;
    private final ClickHouseProperties props;
    private final ClickHouseQueryExecutor executor;
    private final Environment environment;

    public DiagnosticsTools(DataSource dataSource, ClickHouseProperties props,
                            ClickHouseQueryExecutor executor, Environment environment) {
        this.dataSource = dataSource;
        this.props = props;
        this.executor = executor;
        this.environment = environment;
    }

    // ============================================================ 连接池

    @Tool(name = "get_pool_status",
            description = """
                    查看 ClickHouse 连接池（HikariCP）的实时使用情况，用于排查「取数变慢 / 请求排队」类问题。
                    返回 active / idle / total 连接数、等待借连接的线程数、池上限、饱和比例，
                    以及一份 diagnosis 人话解读。
                    status 取值：
                      · OK              健康
                      · FULL            连接全部在用，但暂时没人排队（预警）
                      · SATURATED       有人正在排队等连接 —— 此时应退避重试，改 SQL 没用
                      · NOT_INITIALIZED 连接池还没启动（服务起来后还没有任何一次成功的 ClickHouse 访问）
                    可选参数 probe：为 true 时会真的借一条连接执行 SELECT 1 再归还，
                    用来确认「ClickHouse 现在是否可达」，代价是一次建连耗时（最坏到 connection-timeout）。
                    默认 false。
                    本工具面向运维排查；常规数据分析取数不需要调用。""")
    public PoolStatusResult getPoolStatus(
            @ToolParam(description = "true 则实际借一条连接执行 SELECT 1 探测可达性，默认 false",
                    required = false) Boolean probe) {

        HikariPoolMXBean pool = poolMxBean();

        boolean initialized = pool != null;
        int active = initialized ? pool.getActiveConnections() : 0;
        int idle = initialized ? pool.getIdleConnections() : 0;
        int total = initialized ? pool.getTotalConnections() : 0;
        int waiting = initialized ? pool.getThreadsAwaitingConnection() : 0;
        int max = props.getPoolSize();
        int saturation = max > 0 ? active * 100 / max : 0;

        List<String> diagnosis = new ArrayList<>();
        String status;

        if (!initialized) {
            status = "NOT_INITIALIZED";
            diagnosis.add("连接池还没启动 —— 池是懒建的，服务起来后第一次真正访问 ClickHouse 才会创建。");
            diagnosis.add("如果只是想确认 ClickHouse 是否可达，用 probe=true 再查一次。");
        } else if (waiting > 0) {
            status = "SATURATED";
            diagnosis.add("有 " + waiting + " 个请求正在排队等借连接 —— 池已打满。");
            diagnosis.add("这是「取数变慢」最常见的根因。调用方应退避重试，而不是改 SQL。");
            if (active >= max) {
                diagnosis.add("active(" + active + ") 已达池上限(" + max + ")。"
                        + "若要提容量，先确认 ClickHouse 侧该账号的并发配额 —— "
                        + "所有 Agent 共用同一个账号，配额是大家一起抢的。");
            }
        } else if (active >= max) {
            status = "FULL";
            diagnosis.add("连接全部在用（" + active + "/" + max + "），暂时还没有请求排队。");
            diagnosis.add("属于预警状态：再来一个并发请求就会开始排队。");
        } else {
            status = "OK";
            diagnosis.add("连接池健康：" + active + " 在用、" + idle + " 空闲，上限 " + max + "。");
        }

        Boolean probeOk = null;
        Long probeElapsedMs = null;
        if (Boolean.TRUE.equals(probe)) {
            long t0 = System.currentTimeMillis();
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT 1");
                probeOk = Boolean.TRUE;
            } catch (Exception e) {
                probeOk = Boolean.FALSE;
                ClickHouseMcpException mapped = ClickHouseErrorClassifier.classify(e);
                diagnosis.add("探测失败 [" + mapped.code() + "]：" + mapped.reason());
            }
            probeElapsedMs = System.currentTimeMillis() - t0;
            if (Boolean.TRUE.equals(probeOk)) {
                diagnosis.add("探测成功（SELECT 1），耗时 " + probeElapsedMs + " ms —— ClickHouse 可达。");
            }
        }

        PoolStatusResult.PoolConfig config = new PoolStatusResult.PoolConfig(
                props.getPoolSize(),
                props.getConnectionTimeoutMs(),
                props.getIdleTimeoutMs(),
                props.getMaxLifetimeMs(),
                "jdbc:clickhouse://" + props.getHost() + ":" + props.effectivePort()
                        + "/" + props.getDatabase(),
                "com.clickhouse.jdbc.ClickHouseDriver");

        return new PoolStatusResult(status, initialized, active, idle, total, max,
                waiting, saturation, probeOk, probeElapsedMs, config, diagnosis);
    }

    // ============================================================ 服务状态

    @Tool(name = "get_server_status",
            description = """
                    查看本 MCP 服务自身的运行状态：传输模式、运行时长、JVM 内存与线程数、
                    当前生效的限额配置（含访问级别 READONLY/WRITE/WRITE_WITH_DROP），
                    以及自启动以来的 SQL 执行统计（总数/成功/失败/截断/按错误分类码聚合）。
                    用途：
                      · 运维：确认服务是否健康、改的限额配置有没有生效、最近失败集中在哪一类
                      · Agent 自助：拿到 DatabaseUnavailable 后调一次，可区分
                        「连接池打满（该退避重试）」与「ClickHouse 不可达（原样重试即可）」
                    计数自进程启动累计，重启即清零。
                    本工具面向运维排查；常规数据分析取数不需要调用。""")
    public ServerStatusResult getServerStatus() {
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        Instant startedAt = Instant.ofEpochMilli(rt.getStartTime());

        boolean stdio = environment.getProperty("spring.ai.mcp.server.stdio", Boolean.class, Boolean.FALSE);
        String transport = stdio ? "stdio" : "http";
        String endpoint = null;
        if (!stdio) {
            String mcpEndpoint = environment.getProperty(
                    "spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp");
            String port = environment.getProperty("local.server.port",
                    environment.getProperty("server.port", "8000"));
            endpoint = "http://localhost:" + port + mcpEndpoint;
        }

        Runtime runtime = Runtime.getRuntime();
        long heapMax = runtime.maxMemory();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        ServerStatusResult.Memory memory = new ServerStatusResult.Memory(
                heapUsed / 1024 / 1024,
                heapMax / 1024 / 1024,
                heapMax > 0 ? (int) (heapUsed * 100 / heapMax) : 0);

        String accessMode = !props.isAllowWriteAccess() ? "READONLY"
                : (props.isAllowDrop() ? "WRITE_WITH_DROP" : "WRITE");
        ServerStatusResult.Limits limits = new ServerStatusResult.Limits(
                props.getMaxRows(),
                props.getMaxBytes(),
                props.getQueryTimeoutMs(),
                props.getMetadataMaxRows(),
                props.getOnTruncate().name(),
                props.getPoolSize(),
                accessMode);

        QueryStats stats = executor.snapshot();

        List<String> diagnosis = new ArrayList<>();
        if (stats.total() == 0) {
            diagnosis.add("自启动以来还没有执行过任何 SQL。");
        } else {
            long failRate = stats.failed() * 100 / stats.total();
            diagnosis.add("共执行 " + stats.total() + " 次 SQL，成功 " + stats.succeeded()
                    + "、失败 " + stats.failed() + "（失败率 " + failRate + "%）。");
            if (failRate >= 50) {
                diagnosis.add("失败率偏高，建议查 stderr 日志确认根因。");
            }
            if (!stats.failuresByCode().isEmpty()) {
                diagnosis.add("失败分类分布：" + stats.failuresByCode()
                        + "。InvalidArgument 高说明 Agent 的 SQL 质量差；"
                        + "DatabaseUnavailable / QueryTimeout 高说明是容量或集群问题。");
            }
            if (stats.truncated() > 0) {
                diagnosis.add("有 " + stats.truncated() + " 次结果被预算截断。"
                        + "onTruncate=ERROR 时这类会以 QueryLimitExceeded 计入失败；"
                        + "PARTIAL 时结果不完整，注意别当全集用。");
            }
        }
        if (memory.heapUsedPercent() >= 85) {
            diagnosis.add("堆内存已用 " + memory.heapUsedPercent() + "%，偏高。"
                    + "结果集常驻内存，可考虑调小 max-rows / max-bytes，或加大 -Xmx。");
        }

        return new ServerStatusResult(
                "UP", instanceId(), transport, endpoint, startedAt.toString(), rt.getUptime() / 1000,
                environment.getProperty("spring.ai.mcp.server.version", "unknown"),
                System.getProperty("java.version"),
                ManagementFactory.getThreadMXBean().getThreadCount(),
                memory, limits, stats, diagnosis);
    }

    // ============================================================ 内部

    /**
     * 实例标识：{@code <HOSTNAME>#<pid>}。
     * K8s 会把 HOSTNAME 设成 Pod 名，多副本时一眼能看出这次是哪个实例回答的。
     */
    private static String instanceId() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            try {
                host = java.net.InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "unknown";
            }
        }
        return host + "#" + ProcessHandle.current().pid();
    }

    private HikariPoolMXBean poolMxBean() {
        if (dataSource instanceof HikariDataSource hikari) {
            return hikari.getHikariPoolMXBean();
        }
        return null;
    }
}
