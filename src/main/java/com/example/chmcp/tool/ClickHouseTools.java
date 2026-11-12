package com.example.chmcp.tool;

import com.example.chmcp.config.ClickHouseProperties;
import com.example.chmcp.error.InvalidArgumentException;
import com.example.chmcp.query.ClickHouseQueryExecutor;
import com.example.chmcp.query.QueryResult;
import com.example.chmcp.sql.SqlGuard;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 暴露给 Agent 的三个业务 @Tool 方法（另有两个运维诊断工具在
 * {@link DiagnosticsTools}，可按开关整体关闭）。
 *
 * <p>工具面与 Python 版 mcp-clickhouse 对齐：
 * {@code list_databases} → {@code list_tables} → {@code run_query}。
 * 服务端不做 NL2SQL —— SQL 由 Agent 侧生成，这里只负责校验、执行、限额、报错分类。
 *
 * <h2>为什么有的参数名是 snake_case</h2>
 * Spring AI 2.0 的 {@code @ToolParam} 只有 description / required，<b>没有 name</b>；
 * 生成的 JSON Schema 里的属性名直接来自 Java 反射参数名，而 {@code @JsonProperty}
 * 对 schema 生成<b>不生效</b>（已实测：加了之后 tools/list 里仍是 notLike）。
 * 因此这里刻意把参数写成 {@code not_like} / {@code page_size} 这种形式 ——
 * Java 标识符允许下划线，这样能保证对外的工具契约与 Python 版完全一致，
 * 已有的提示词 / 客户端调用代码不用改。
 *
 * <h2>错误处理：故意不写 try/catch</h2>
 * 工具方法让 {@code ClickHouseMcpException} 直接抛出去，Spring AI 框架会自动置
 * MCP 的 {@code isError=true} 并原样透传 JSON 消息（见 ClickHouseMcpException 的注释）。
 * 所以每个工具方法只写「顺利路径」，异常路径全部交给
 * {@code ClickHouseQueryExecutor} + {@code ClickHouseErrorClassifier}。
 *
 * <p>所有工具都不接受连接参数（主机 / 账号 / 密码），连接信息只从服务端配置读。
 */
@Component
public class ClickHouseTools {

    /** list_tables 单页默认大小（与 Python 版一致）。 */
    private static final int DEFAULT_PAGE_SIZE = 100;

    private final ClickHouseQueryExecutor executor;
    private final ClickHouseProperties props;

    public ClickHouseTools(ClickHouseQueryExecutor executor, ClickHouseProperties props) {
        this.executor = executor;
        this.props = props;
    }

    @Tool(name = "list_databases",
            description = """
                    列出 ClickHouse 上当前账号可见的所有数据库名称。取数的第一步，用来确认库名。
                    返回 { databases: [...], truncated: bool }。
                    truncated 为 true 时列表不完整。
                    失败时返回 isError=true，内容为 {"code","message","hint","retryable"} 形式的 JSON。""")
    public ListDatabasesResult listDatabases() {
        QueryResult r = executor.executeMetadata("SHOW DATABASES");
        return new ListDatabasesResult(firstColumn(r), r.truncated());
    }

    @Tool(name = "list_tables",
            description = """
                    列出指定数据库中的表及其元数据（引擎、注释、建表语句、依赖）。
                    参数：
                      · database                  必填，数据库名（ClickHouse 大小写敏感）
                      · like                      可选，按表名过滤的 LIKE 模式（如 'events_%'）
                      · not_like                  可选，排除匹配该模式的表名
                      · include_detailed_columns  可选，true 时附带每张表的列名/类型/默认值/注释
                        （列多时会显著变大，建议先 false 摸清表清单，再对目标表单独查）
                      · page_size / page_token    可选分页；上一页返回的 nextPageToken 原样传回即可
                    返回 { database, tables: [...], nextPageToken, truncated }。
                    nextPageToken 为 null 表示已到末页；truncated 为 true 时清单不完整，
                    不要据此判断某张表不存在。
                    失败时返回 isError=true，内容为 {"code","message","hint","retryable"} 形式的 JSON。""")
    public ListTablesResult listTables(
            @ToolParam(description = "数据库名，只允许字母/数字/下划线（大小写敏感）")
            String database,
            @ToolParam(description = "按表名过滤的 LIKE 模式，如 'events_%'", required = false)
            String like,
            @ToolParam(description = "排除匹配该 LIKE 模式的表名", required = false)
            String not_like,
            @ToolParam(description = "true 则附带每张表的列详情，默认 false", required = false)
            Boolean include_detailed_columns,
            @ToolParam(description = "单页大小，默认 100", required = false)
            Integer page_size,
            @ToolParam(description = "上一页返回的 nextPageToken，首页不传", required = false)
            String page_token) {

        String db = (database == null || database.isBlank()) ? props.getDatabase() : database.trim();
        String dbIdent = SqlGuard.requireIdentifier(db, "database name");

        int size = page_size == null ? DEFAULT_PAGE_SIZE : page_size;
        if (size < 1 || size > props.getMetadataMaxRows()) {
            throw new InvalidArgumentException(
                    "page_size out of range: " + size,
                    "page_size 取值范围 1 ~ " + props.getMetadataMaxRows() + "。");
        }
        int offset = parsePageToken(page_token);

        StringBuilder sql = new StringBuilder(
                "SELECT database, name, engine, comment, create_table_query,"
                        + " dependencies_database, dependencies_table FROM system.tables WHERE database = ")
                .append(SqlGuard.requireLiteral(db, "database"));
        if (like != null && !like.isBlank()) {
            sql.append(" AND name LIKE ").append(SqlGuard.requireLiteral(like.trim(), "like"));
        }
        if (not_like != null && !not_like.isBlank()) {
            sql.append(" AND name NOT LIKE ").append(SqlGuard.requireLiteral(not_like.trim(), "not_like"));
        }
        // 多取一行用来判断有没有下一页（免去一次 COUNT）
        sql.append(" ORDER BY name LIMIT ").append(size + 1).append(" OFFSET ").append(offset);

        QueryResult r = executor.executeMetadata(sql.toString());

        List<TableInfo> tables = new ArrayList<>();
        List<String> pageTableNames = new ArrayList<>();
        boolean hasMore = false;
        for (List<Object> row : r.rows()) {
            if (tables.size() >= size) {
                hasMore = true;
                break;
            }
            String tableDb = str(row, 0);
            String name = str(row, 1);
            pageTableNames.add(name);
            tables.add(new TableInfo(
                    tableDb,
                    name,
                    str(row, 2),
                    str(row, 3),
                    str(row, 4),
                    mergeDependencies(str(row, 5), str(row, 6)),
                    null));
        }

        if (Boolean.TRUE.equals(include_detailed_columns) && !pageTableNames.isEmpty()) {
            attachColumns(db, pageTableNames, tables);
        }

        String nextToken = hasMore ? String.valueOf(offset + size) : null;
        return new ListTablesResult(dbIdent.replace("`", ""), tables, nextToken, r.truncated());
    }

    @Tool(name = "run_query",
            description = """
                    执行一条 SQL 并返回结果。默认只读模式：只接受单条语句，首词必须是
                    SELECT / WITH / SHOW / DESCRIBE / EXPLAIN / EXISTS，
                    且服务端以 readonly=1 强制 —— 写操作会被 ClickHouse 直接拒绝。
                    （管理员开启 allow-write-access / allow-drop 后才放行 INSERT/CREATE
                    与 DROP/TRUNCATE 等。）
                    成功时返回 { columns, rows, rowCount, truncated, elapsedMs }：
                      · 超出 JS 安全范围的整数与 DECIMAL 以字符串形式返回，不会丢精度
                      · truncated 为 true 表示结果被预算截断、不是全集，应改用聚合或加过滤条件
                    失败时返回 isError=true，内容为 {"code","message","hint","retryable"} 形式的 JSON：
                      · code=InvalidArgument      SQL 有问题，改一版可重试
                      · code=AccessDenied         无权限或只读模式拦截，改 SQL 无用，别再试
                      · code=QueryTimeout         超时，加 LIMIT 或先聚合
                      · code=QueryLimitExceeded   结果超预算，需聚合或分批
                      · code=DatabaseUnavailable  连不上 ClickHouse，原样重试即可
                      · code=InternalError        服务端异常，不要重试
                    请先读 hint 字段再决定下一步。""")
    public QueryResult runQuery(
            @ToolParam(description = "单条 SQL 语句，不要带结尾分号") String sql) {
        String safe = SqlGuard.validate(sql, props.isAllowWriteAccess(), props.isAllowDrop());
        return executor.execute(safe);
    }

    // ============================================================ 内部

    /** 批量查 system.columns，把列详情按表名归到对应的 TableInfo 上。 */
    private void attachColumns(String db, List<String> tableNames, List<TableInfo> tables) {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < tableNames.size(); i++) {
            if (i > 0) {
                in.append(", ");
            }
            in.append(SqlGuard.requireLiteral(tableNames.get(i), "table name"));
        }
        String sql = "SELECT table, name, type, default_kind, default_expression, comment"
                + " FROM system.columns WHERE database = "
                + SqlGuard.requireLiteral(db, "database")
                + " AND table IN (" + in + ")"
                + " ORDER BY table, position";

        QueryResult r = executor.executeMetadata(sql);

        Map<String, List<TableInfo.ColumnInfo>> byTable = new LinkedHashMap<>();
        for (List<Object> row : r.rows()) {
            byTable.computeIfAbsent(str(row, 0), k -> new ArrayList<>())
                    .add(new TableInfo.ColumnInfo(
                            str(row, 1), str(row, 2), str(row, 3), str(row, 4), str(row, 5)));
        }
        for (int i = 0; i < tables.size(); i++) {
            TableInfo t = tables.get(i);
            List<TableInfo.ColumnInfo> cols = byTable.get(t.name());
            if (cols != null) {
                tables.set(i, new TableInfo(t.database(), t.name(), t.engine(), t.comment(),
                        t.createTableQuery(), t.dependencies(), cols));
            }
        }
    }

    /** system.tables 的依赖是两列数组（库数组 + 表数组），合并成 ["db.tbl", ...] 更易读。 */
    private static List<String> mergeDependencies(String dbArray, String tableArray) {
        List<String> dbs = parseArrayLiteral(dbArray);
        List<String> tbls = parseArrayLiteral(tableArray);
        int n = Math.min(dbs.size(), tbls.size());
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(dbs.get(i) + "." + tbls.get(i));
        }
        return out;
    }

    /**
     * JDBC 层把 Array(String) 读成 "['a','b']" 形式的字符串，这里做最小解析。
     * 依赖名都是标识符，不含引号/逗号转义场景，这个解析足够。
     */
    private static List<String> parseArrayLiteral(String s) {
        if (s == null || s.length() < 2 || !s.startsWith("[") || !s.endsWith("]")) {
            return List.of();
        }
        String inner = s.substring(1, s.length() - 1).trim();
        if (inner.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : inner.split(",")) {
            String v = part.trim();
            if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) {
                v = v.substring(1, v.length() - 1);
            }
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    private static int parsePageToken(String pageToken) {
        if (pageToken == null || pageToken.isBlank()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(pageToken.trim());
            if (offset < 0) {
                throw new NumberFormatException("negative");
            }
            return offset;
        } catch (NumberFormatException e) {
            throw new InvalidArgumentException(
                    "Invalid page_token: " + pageToken,
                    "page_token 应原样使用上一次返回的 nextPageToken；首页不传即可。");
        }
    }

    private static String str(List<Object> row, int i) {
        Object v = (i < row.size()) ? row.get(i) : null;
        return v == null ? "" : String.valueOf(v);
    }

    private static List<String> firstColumn(QueryResult r) {
        List<String> out = new ArrayList<>(r.rows().size());
        for (List<Object> row : r.rows()) {
            if (!row.isEmpty() && row.get(0) != null) {
                out.add(String.valueOf(row.get(0)));
            }
        }
        return out;
    }
}
