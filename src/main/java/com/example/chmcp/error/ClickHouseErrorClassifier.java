package com.example.chmcp.error;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.Locale;

/**
 * 把底层异常（ClickHouse JDBC / HikariCP / 网络层）映射成本服务的分类异常。
 *
 * <p><b>所有分类规则集中在这一个类里</b> —— 要调规则只改这里，不用翻业务代码。
 *
 * <p>ClickHouse 的报错文本带稳定错误码（{@code Code: NNN}），优先按码匹配，
 * 其次按关键词。常用码：
 * <ul>
 *   <li>60  表不存在 &nbsp;·&nbsp; 81  库不存在 &nbsp;·&nbsp; 47  未知列/标识符
 *       &nbsp;·&nbsp; 62  语法错误 → InvalidArgument</li>
 *   <li>516 认证失败 &nbsp;·&nbsp; 497 权限不足 &nbsp;·&nbsp; 164 readonly 模式写了
 *       → AccessDenied</li>
 *   <li>160 超 max_execution_time &nbsp;·&nbsp; 394 查询被取消 → QueryTimeout</li>
 * </ul>
 *
 * <h2>判定顺序（不能随便调）</h2>
 * <ol>
 *   <li>已经是 {@link ClickHouseMcpException}（或藏在 cause 链里）→ 原样返回</li>
 *   <li>{@link SQLTransientConnectionException} → 连接不可用</li>
 *   <li>连接类关键词 → 连接不可用。<b>必须排在超时之前</b>：HikariCP 借不到连接的
 *       报错含 "timed out"，先判超时会把「借不到连接」误报成「查询超时」，
 *       两者处置方式完全不同（一个该等，一个该改 SQL）</li>
 *   <li>权限/只读类 → 权限不足</li>
 *   <li>SQL 编译期错误 → 参数非法</li>
 *   <li>{@link SQLTimeoutException} 或超时关键词 → 查询超时</li>
 *   <li>其余 {@link SQLException} → 兜底为内部错误</li>
 * </ol>
 */
public final class ClickHouseErrorClassifier {

    private ClickHouseErrorClassifier() {}

    /** 连接层问题：服务不可达、连接被拒、连接池借不到连接。 */
    private static final String[] UNAVAILABLE = {
            "connection refused",
            "connect timed out",
            "connection timed out",
            "failed to connect",
            "could not connect",
            "cannot connect",
            "unable to connect",
            "connection is closed",
            "connection is not available",
            "no more connections",
            "connection reset",
            "broken pipe",
            "already closed",
            "unknownhost",
            "unknown host",
            "no route to host",
            // ClickHouse 集群全员下线
            "all hosts are unavailable",
    };

    /**
     * 权限问题：认证失败 / 授权不足 / readonly 拦截。
     * 164 = READONLY：readonly=1 模式下执行写操作被服务端拒绝 ——
     * 归到 AccessDenied，hint 里说明如何开写开关。
     */
    private static final String[] DENIED = {
            "code: 516",          // AUTHENTICATION_FAILED
            "code: 497",          // ACCESS_DENIED
            "code: 164",          // READONLY
            "access denied",
            "authentication failed",
            "not authorized",
            "unauthorized",
            "permission",
            "denied",
            "not enough privileges",
            "readonly mode",
            "read-only mode",
            "cannot execute query in readonly",
    };

    /** SQL 编译期错误 —— 语句本身有问题。 */
    private static final String[] INVALID_SQL = {
            "code: 62",           // SYNTAX_ERROR
            "code: 60",           // UNKNOWN_TABLE
            "code: 81",           // UNKNOWN_DATABASE
            "code: 47",           // UNKNOWN_IDENTIFIER
            "code: 386",          // THERE_IS_NO_COLUMN (No such column)
            "syntax error",
            "unknown table",
            "unknown database",
            "unknown identifier",
            "no such column",
            "there is no column",
            "doesn't exist",
            "does not exist",
            "unexpected token",
            "cannot resolve",
            "unknown function",
            "no such function",
    };

    /** 超时关键词。160 = TIMEOUT_EXCEEDED（max_execution_time）。 */
    private static final String[] TIMEOUT = {
            "code: 160",
            "code: 159",
            "code: 394",          // QUERY_WAS_CANCELLED
            "timeout exceeded",
            "timed out",
            "timeout",
            "cancelled",
    };

    public static ClickHouseMcpException classify(Throwable t) {
        if (t == null) {
            return new InternalErrorException("Unknown failure (no exception)",
                    "服务端未提供错误详情，请重试或联系管理员。", null);
        }

        // ① 已经是本服务的分类异常：顺着 cause 链找，别重复包装
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ClickHouseMcpException known) {
                return known;
            }
        }

        // ② HikariCP 借连接超时
        if (t instanceof SQLTransientConnectionException) {
            return unavailable(t);
        }

        String msg = collectMessages(t);

        // ③ 连接层 —— 必须在超时之前
        if (containsAny(msg, UNAVAILABLE)) {
            return unavailable(t);
        }

        // ④ 权限 / 只读拦截
        if (containsAny(msg, DENIED)) {
            boolean readonly = msg.contains("code: 164") || msg.contains("readonly");
            return new AccessDeniedException(sanitize(t.getMessage()),
                    readonly
                            ? "服务以只读模式运行（readonly=1），写操作被 ClickHouse 服务端拒绝。"
                              + "如确需写入，请管理员开启 clickhouse-mcp.allow-write-access"
                              + "（DROP/TRUNCATE 等还需 allow-drop）；否则请改用只读查询。"
                            : "该账号无权访问目标库表。这是服务端授权决定的，换个 SQL 写法也没用 —— "
                              + "请换一张有权限的表，或联系数据管理员开权限。", t);
        }

        // ⑤ SQL 编译期错误
        if (containsAny(msg, INVALID_SQL)) {
            return new InvalidArgumentException(sanitize(t.getMessage()),
                    "库表名或 SQL 语法有问题。先用 list_databases / list_tables 确认名称"
                            + "（ClickHouse 大小写敏感），再检查引号、括号与关键字拼写。", t);
        }

        // ⑥ 超时
        if (t instanceof SQLTimeoutException || containsAny(msg, TIMEOUT)) {
            return new QueryTimeoutException(sanitize(t.getMessage()),
                    "查询超出时间预算。请加 LIMIT、缩小时间范围，或先聚合再取数。", t);
        }

        // ⑦ 兜底：不是 Agent 能通过改 SQL 解决的问题
        return new InternalErrorException(sanitize(t.getMessage()),
                "服务端执行失败，且不属于已知的可自愈类型。不要重试同样的请求，请联系管理员排查服务端日志。", t);
    }

    private static DatabaseUnavailableException unavailable(Throwable t) {
        return new DatabaseUnavailableException(sanitize(t.getMessage()),
                "连接 ClickHouse 失败，属基础设施问题而非 SQL 问题。"
                        + "请稍后原样重试，不要修改 SQL —— 改 SQL 解决不了这个。", t);
    }

    /** 把 cause 链上的消息拼起来一起匹配，避免漏掉被包在里层的关键词。 */
    private static String collectMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (Throwable c = t; c != null && depth < 6; c = c.getCause(), depth++) {
            if (c.getMessage() != null) {
                sb.append(c.getMessage()).append(" | ");
            }
            if (c == c.getCause()) {
                break;
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String haystack, String[] needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 抹掉连接串与口令 —— 异常文本可能把 JDBC URL（可能含密码）带出来，
     * 而这些内容会被回给模型，必须过滤。
     */
    public static String sanitize(String msg) {
        if (msg == null) {
            return "";
        }
        String s = msg.replaceAll("(?i)password=\\S+", "password=***");
        s = s.replaceAll("(?i)jdbc:clickhouse://\\S+", "jdbc:clickhouse://***");
        s = s.replaceAll("(?i)jdbc:ch://\\S+", "jdbc:ch://***");
        s = s.replaceAll("(?i)user=\\S+", "user=***");
        return s.length() > 2000 ? s.substring(0, 2000) + "..." : s;
    }

    /** 判断某个异常会被分到哪一类。单测与日志都用它。 */
    public static String classifyCode(Throwable t) {
        return classify(t).code();
    }
}
