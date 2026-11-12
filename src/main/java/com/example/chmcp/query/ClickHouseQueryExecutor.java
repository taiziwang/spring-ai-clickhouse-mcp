package com.example.chmcp.query;

import com.example.chmcp.config.ClickHouseProperties;
import com.example.chmcp.error.ClickHouseErrorClassifier;
import com.example.chmcp.error.ClickHouseMcpException;
import com.example.chmcp.error.QueryLimitExceededException;
import com.example.chmcp.error.QueryTimeoutException;
import com.example.chmcp.util.JdbcValues;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * 查询执行器：借连接 → 设预算 → 跑 → 截断 → 还连接。
 *
 * <p>三件事是这个类存在的理由，缺一个网关就会被 Agent 打挂：
 * <ol>
 *   <li><b>结果集截断</b> —— ClickHouse 侧的授权拦不住
 *       {@code SELECT * FROM 一张几十亿行的表}，但 MCP Server 的堆内存会先死。
 *       行数与字节双预算，任一超限即停止读取。</li>
 *   <li><b>超时 + 真正取消</b> —— 池化模型下一个慢查询会占住一条池连接，
 *       池被耗尽就等于整个网关对全部 Agent 不可用。到点必须
 *       {@code Statement.cancel()}，ClickHouse 侧收到取消会中止执行
 *       （HTTP 连接断开即取消，等价 Python 版的 KILL QUERY）。</li>
 *   <li><b>连接一定还回去</b> —— try-with-resources，异常路径也不例外。</li>
 * </ol>
 *
 * <p>失败一律转成 {@link ClickHouseMcpException} 的子类抛出（见
 * {@link ClickHouseErrorClassifier}），由上层框架置 MCP 的 {@code isError=true}。
 *
 * <p>关于超时实现：不用 {@code setQueryTimeout} 作为唯一手段，
 * 以「独立看门狗线程到点 cancel」为主，{@code setQueryTimeout} 只作辅助。
 */
@Component
public class ClickHouseQueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseQueryExecutor.class);

    private final DataSource dataSource;
    private final ClickHouseProperties props;

    // ---- 运维计数：给 get_server_status 用 ----
    // 用 LongAdder 而不是 AtomicLong —— 高并发下写竞争更小，读略弱但这里只在诊断时读。
    private final LongAdder totalCount = new LongAdder();
    private final LongAdder succeededCount = new LongAdder();
    private final LongAdder failedCount = new LongAdder();
    private final LongAdder truncatedCount = new LongAdder();
    private final ConcurrentHashMap<String, LongAdder> failuresByCode = new ConcurrentHashMap<>();

    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "clickhouse-query-watchdog");
        t.setDaemon(true);
        return t;
    });

    public ClickHouseQueryExecutor(DataSource clickHouseDataSource, ClickHouseProperties props) {
        this.dataSource = clickHouseDataSource;
        this.props = props;
    }

    /** 执行用户查询：走配置的行数/字节预算，并遵守 on-truncate 策略。 */
    public QueryResult execute(String sql) {
        return run(sql, props.getMaxRows(), props.getMaxBytes(), true);
    }

    /** 执行服务端自发的元数据语句：行数预算更小，且截断不算失败。 */
    public QueryResult executeMetadata(String sql) {
        return run(sql, props.getMetadataMaxRows(), props.getMaxBytes(), false);
    }

    private QueryResult run(String sql, int maxRows, long maxBytes, boolean enforceTruncatePolicy) {
        long started = System.currentTimeMillis();
        AtomicBoolean timedOut = new AtomicBoolean(false);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            applyStatementOptions(stmt);

            ScheduledFuture<?> killer = watchdog.schedule(() -> {
                timedOut.set(true);
                try {
                    stmt.cancel();
                    log.warn("查询超时（{}ms），已调用 Statement.cancel(): {}",
                            props.getQueryTimeoutMs(), abbreviate(sql));
                } catch (Exception e) {
                    log.warn("Statement.cancel() 失败: {}", e.toString());
                }
            }, props.getQueryTimeoutMs(), TimeUnit.MILLISECONDS);

            boolean isSelect = returnsRows(sql);
            try {
                if (isSelect) {
                    try (ResultSet rs = stmt.executeQuery(sql)) {
                        return collect(rs, sql, started, maxRows, maxBytes,
                                enforceTruncatePolicy, timedOut);
                    }
                } else {
                    // 写模式下的 INSERT/CREATE/DDL：没有结果集，返回受影响行数占位
                    int affected = stmt.executeUpdate(sql);
                    long elapsed = System.currentTimeMillis() - started;
                    if (timedOut.get()) {
                        throw new QueryTimeoutException(
                                "Statement exceeded the " + props.getQueryTimeoutMs()
                                        + " ms time budget (it completed in " + elapsed
                                        + " ms before the cancellation took effect)",
                                "语句超出时间预算。DDL/写入请确认目标表规模，或拆分执行。");
                    }
                    totalCount.increment();
                    succeededCount.increment();
                    log.info("语句完成: affected={}, elapsedMs={}, sql={}",
                            affected, elapsed, abbreviate(sql));
                    return new QueryResult(List.of("affectedRows"),
                            List.of(List.of(affected)), 1, false, elapsed);
                }
            } catch (SQLException e) {
                if (timedOut.get()) {
                    throw new QueryTimeoutException(
                            "Query exceeded the " + props.getQueryTimeoutMs()
                                    + " ms time budget and was cancelled",
                            "查询超出时间预算已被取消。请加 LIMIT、缩小时间范围，或先聚合再取数。", e);
                }
                throw e;
            } finally {
                killer.cancel(false);
            }

        } catch (ClickHouseMcpException e) {
            recordFailure(e.code());
            log.warn("查询失败 [{}] {} | sql={}", e.code(), e.reason(), abbreviate(sql));
            throw e;
        } catch (SQLException e) {
            // 借连接失败、网络断开、ClickHouse 报错等统一在这里分类
            ClickHouseMcpException mapped = ClickHouseErrorClassifier.classify(e);
            recordFailure(mapped.code());
            log.warn("查询失败 [{}] {} | sql={}", mapped.code(), mapped.reason(), abbreviate(sql));
            throw mapped;
        } catch (RuntimeException e) {
            ClickHouseMcpException mapped = ClickHouseErrorClassifier.classify(e);
            recordFailure(mapped.code());
            log.error("查询出现未预期异常 [{}] | sql={}", mapped.code(), abbreviate(sql), e);
            throw mapped;
        }
    }

    private QueryResult collect(ResultSet rs, String sql, long started, int maxRows,
                                long maxBytes, boolean enforceTruncatePolicy,
                                AtomicBoolean timedOut) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int columnCount = md.getColumnCount();

        List<String> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(md.getColumnLabel(i));
        }

        List<List<Object>> rows = new ArrayList<>();
        long bytes = 0;
        boolean truncated = false;

        while (rs.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            List<Object> row = JdbcValues.readRow(rs, md);
            long rowBytes = JdbcValues.estimateBytes(row);
            if (bytes + rowBytes > maxBytes) {
                truncated = true;
                break;
            }
            rows.add(row);
            bytes += rowBytes;
        }

        long elapsed = System.currentTimeMillis() - started;

        // ⚠️ 超时检查必须在【成功路径】上也做一次：看门狗的 cancel 不保证一定先生效，
        // 查询可能恰好在 cancel 前跑完正常返回 —— 那种情况不能当成成功报出去。
        if (timedOut.get()) {
            throw new QueryTimeoutException(
                    "Query exceeded the " + props.getQueryTimeoutMs()
                            + " ms time budget (it completed in " + elapsed
                            + " ms before the cancellation took effect)",
                    "查询超出时间预算。请加 LIMIT、缩小时间范围，或先聚合再取数。");
        }

        if (truncated && enforceTruncatePolicy
                && props.getOnTruncate() == ClickHouseProperties.TruncatePolicy.ERROR) {
            throw new QueryLimitExceededException(
                    "Result exceeded the budget (" + maxRows + " rows / " + maxBytes
                            + " bytes) after " + rows.size() + " rows",
                    "结果超出预算，已按当前策略拒绝（不返回部分数据）。"
                            + "请用聚合函数（COUNT / SUM / AVG / groupArray 截断）减少行数、"
                            + "加过滤条件缩小范围，或用 ORDER BY + LIMIT 只取 Top N。");
        }

        totalCount.increment();
        succeededCount.increment();
        if (truncated) {
            truncatedCount.increment();
        }

        log.info("查询完成: rows={}, truncated={}, bytes={}, elapsedMs={}, sql={}",
                rows.size(), truncated, bytes, elapsed, abbreviate(sql));
        return new QueryResult(columns, rows, rows.size(), truncated, elapsed);
    }

    /**
     * 粗判语句是否有结果集：SELECT 系与 SHOW/DESCRIBE/EXISTS/EXPLAIN 走 executeQuery，
     * 其余（写模式下的 INSERT/CREATE/DDL）走 executeUpdate。
     */
    private static boolean returnsRows(String sql) {
        int i = 0;
        while (i < sql.length() && !Character.isLetter(sql.charAt(i))) {
            i++;
        }
        int j = i;
        while (j < sql.length() && Character.isLetterOrDigit(sql.charAt(j))) {
            j++;
        }
        String head = sql.substring(i, j).toUpperCase(java.util.Locale.ROOT);
        return switch (head) {
            case "SELECT", "WITH", "SHOW", "DESCRIBE", "DESC", "EXISTS", "EXPLAIN" -> true;
            default -> false;
        };
    }

    /**
     * 自启动以来的累计统计，供运维诊断工具（get_server_status）读取。
     * 刻意只做进程内累计、不持久化 —— 重启清零是可以接受的语义。
     */
    public QueryStats snapshot() {
        Map<String, Long> byCode = new LinkedHashMap<>();
        failuresByCode.forEach((code, counter) -> byCode.put(code, counter.sum()));
        return new QueryStats(totalCount.sum(), succeededCount.sum(), failedCount.sum(),
                truncatedCount.sum(), byCode);
    }

    private void recordFailure(String code) {
        totalCount.increment();
        failedCount.increment();
        failuresByCode.computeIfAbsent(code, k -> new LongAdder()).increment();
    }

    private void applyStatementOptions(Statement stmt) {
        try {
            stmt.setFetchSize(props.getFetchSize());
        } catch (Exception e) {
            log.debug("setFetchSize 不被驱动支持，忽略: {}", e.toString());
        }
        try {
            // 辅助手段，不作为唯一保障（见类注释）
            stmt.setQueryTimeout((int) Math.max(1, props.getQueryTimeoutMs() / 1000));
        } catch (Exception e) {
            log.debug("setQueryTimeout 不被驱动支持，忽略: {}", e.toString());
        }
    }

    private static String abbreviate(String sql) {
        if (sql == null) {
            return "null";
        }
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "...";
    }

    @PreDestroy
    void shutdown() {
        watchdog.shutdownNow();
    }
}
