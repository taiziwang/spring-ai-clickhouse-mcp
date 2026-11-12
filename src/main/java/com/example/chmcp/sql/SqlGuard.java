package com.example.chmcp.sql;

import com.example.chmcp.error.AccessDeniedException;
import com.example.chmcp.error.InvalidArgumentException;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 语句守卫，三级开关，语义与 Python 版 mcp-clickhouse 一致：
 *
 * <ol>
 *   <li><b>只读（默认，allow-write-access=false）</b>：只允许
 *       SELECT / WITH / SHOW / DESCRIBE / DESC / EXPLAIN / EXISTS。
 *       同时 JDBC URL 带 {@code readonly=1}，由 ClickHouse 服务端强制 ——
 *       这里只是提前拦截，省一次网络往返并给出更清楚的报错。</li>
 *   <li><b>写（allow-write-access=true）</b>：额外放行 INSERT / CREATE。
 *       对应 CLICKHOUSE_ALLOW_WRITE_ACCESS。</li>
 *   <li><b>危险操作（allow-drop=true，需先开写）</b>：额外放行
 *       DROP / TRUNCATE / ALTER / RENAME / ATTACH / DETACH / OPTIMIZE / KILL
 *       以及 UPDATE / DELETE（ClickHouse 的轻量更新/删除）。
 *       对应 CLICKHOUSE_ALLOW_DROP。</li>
 * </ol>
 *
 * <p><b>定位：体验优化，不是安全边界。</b>
 * 真正的安全边界是 ClickHouse 侧对该账号的授权（以及只读模式下的 readonly=1）。
 *
 * <p>任何级别都<b>永远禁止</b>：GRANT / REVOKE / SYSTEM / SET / USE / INTO OUTFILE
 * / INTO INFILE —— 授权与运维命令不属于取数网关的职责；SET/USE 会污染
 * 池化连接的会话状态，影响下一位借用者。
 *
 * <p>失败分两类，语义刻意分开（这决定 Agent 要不要重试）：
 * <ul>
 *   <li>{@link AccessDeniedException}（retryable=false）—— 被访问级别或策略拦下的语句：
 *       只读模式下的写操作、未开 allow-drop 时的 DROP/TRUNCATE、GRANT/SET/SYSTEM 等。
 *       <b>改 SQL 也没用</b>，只能找管理员开开关或换一种做法。</li>
 *   <li>{@link InvalidArgumentException}（retryable=true）—— SQL 本身有问题：
 *       空语句、多语句、非法标识符。改一版就能重试。</li>
 * </ul>
 *
 * <p>已知取舍：禁用词用 {@code \b} 词边界匹配，字符串字面量里出现
 * {@code 'DROP'} 之类会被误杀。误杀优于漏放。
 */
public final class SqlGuard {

    /** 只读模式允许的语句首词。EXISTS 用于快速探表是否存在。 */
    private static final Set<String> READ_HEADS = Set.of(
            "SELECT", "WITH", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "EXISTS");

    /** allow-write-access 额外放行的首词。 */
    private static final Set<String> WRITE_HEADS = Set.of("INSERT", "CREATE");

    /**
     * allow-drop 额外放行的首词。
     *
     * <p>⚠️ 必须包含 {@code DELETE} —— ClickHouse 的轻量删除是独立语句
     * {@code DELETE FROM t WHERE ...}（不是 ALTER 的子句），首词就是 DELETE。
     * 漏了它会导致「开了 allow-drop 也删不掉」。
     * UPDATE 在 ClickHouse 里没有独立语句形态（只能是 {@code ALTER TABLE ... UPDATE}），
     * 由 ALTER 覆盖。
     */
    private static final Set<String> DROP_HEADS = Set.of(
            "DROP", "TRUNCATE", "ALTER", "RENAME", "ATTACH", "DETACH", "OPTIMIZE", "KILL",
            "DELETE");

    /**
     * 「真实存在但被当前访问级别拦下」的语句首词。
     *
     * <p>用于区分两类失败：
     * <ul>
     *   <li>首词在这里 → {@link AccessDeniedException}（改 SQL 无用，要开开关）</li>
     *   <li>首词不在这里也不是只读语句 → 大概率是拼写错误（如 SELEC）→
     *       {@link InvalidArgumentException}（改一版可重试）</li>
     * </ul>
     */
    private static final Set<String> KNOWN_BLOCKED_HEADS = Set.of(
            "INSERT", "CREATE", "DROP", "TRUNCATE", "ALTER", "RENAME", "ATTACH", "DETACH",
            "OPTIMIZE", "KILL", "DELETE", "UPDATE", "MERGE", "REPLACE", "EXCHANGE",
            "GRANT", "REVOKE", "SYSTEM", "SET", "USE");

    /** 永远禁止的关键字（任何开关下都拦）。 */
    private static final Pattern ALWAYS_FORBIDDEN = Pattern.compile(
            "(?i)\\b(GRANT|REVOKE|SYSTEM|SET|USE|INFILE|OUTFILE)\\b");

    /** 只读模式下禁止的写关键字。 */
    private static final Pattern WRITE_FORBIDDEN = Pattern.compile(
            "(?i)\\b(INSERT|CREATE|UPDATE|DELETE|DROP|TRUNCATE|ALTER|RENAME|ATTACH|DETACH|"
          + "OPTIMIZE|KILL|MERGE|REPLACE|EXCHANGE)\\b");

    /** 写模式（未开 drop）下禁止的危险关键字。 */
    private static final Pattern DROP_FORBIDDEN = Pattern.compile(
            "(?i)\\b(UPDATE|DELETE|DROP|TRUNCATE|ALTER|RENAME|ATTACH|DETACH|OPTIMIZE|KILL)\\b");

    /** 行注释：-- 到行尾。 */
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");

    /** 块注释，允许跨行。 */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** 合法标识符：字母或下划线开头，后接字母/数字/下划线。 */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private SqlGuard() {}

    /**
     * 校验通过则返回规范化后的 SQL（已剥注释、已去尾分号）。
     *
     * @param sql        原始 SQL
     * @param allowWrite 是否放行 INSERT / CREATE
     * @param allowDrop  是否放行 DROP / TRUNCATE / ALTER / UPDATE / DELETE 等
     */
    public static String validate(String sql, boolean allowWrite, boolean allowDrop) {
        String hint = hint(allowWrite, allowDrop);
        if (sql == null || sql.isBlank()) {
            throw new InvalidArgumentException("SQL is empty", hint);
        }
        String s = stripComments(sql).trim();

        // 去掉允许的结尾分号；此后仍含分号即视为多语句，直接拒绝
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        if (s.contains(";")) {
            throw new InvalidArgumentException(
                    "Only a single statement is allowed (found ';' after stripping the trailing one)",
                    hint);
        }
        if (s.isEmpty()) {
            throw new InvalidArgumentException("SQL is empty", hint);
        }

        String head = firstWord(s);
        boolean headAllowed = READ_HEADS.contains(head)
                || (allowWrite && WRITE_HEADS.contains(head))
                || (allowWrite && allowDrop && DROP_HEADS.contains(head));
        if (!headAllowed) {
            if (KNOWN_BLOCKED_HEADS.contains(head)) {
                throw new AccessDeniedException("Statement type not allowed: " + head, hint);
            }
            // 不是已知语句 —— 多半是拼写错误（SELEC / SELEKT），让 Agent 改 SQL 重试
            throw new InvalidArgumentException(
                    "Unknown statement type: " + head,
                    "首词不是可识别的语句（可能是拼写错误）。只读模式支持 "
                            + "SELECT / WITH / SHOW / DESCRIBE / EXPLAIN / EXISTS。");
        }
        if (ALWAYS_FORBIDDEN.matcher(s).find()) {
            throw new AccessDeniedException(
                    "SQL contains an always-forbidden operation (GRANT/REVOKE/SYSTEM/SET/USE/OUTFILE)", hint);
        }

        // SHOW / DESCRIBE / DESC / EXISTS 是纯元数据语句，语句体里出现 CREATE 等字样
        // 是正常的（最典型的是 SHOW CREATE TABLE x）—— 对它们不做写关键字扫描。
        // EXPLAIN 不在此列：EXPLAIN INSERT 虽然不执行，但放行会给 Agent 错误暗示。
        boolean metadataStatement = Set.of("SHOW", "DESCRIBE", "DESC", "EXISTS").contains(head);
        Pattern levelForbidden = metadataStatement ? null
                : (!allowWrite ? WRITE_FORBIDDEN : (allowDrop ? null : DROP_FORBIDDEN));
        if (levelForbidden != null && levelForbidden.matcher(s).find()) {
            throw new AccessDeniedException(
                    "SQL contains an operation blocked by the current access level", hint);
        }
        return s;
    }

    /**
     * 校验库名/表名并返回反引号包裹的安全标识符。
     *
     * <p>元数据类工具会用调用方传入的名字拼 SQL，若直接拼接就等于把注入口交给模型。
     * 这里强制白名单字符集，只允许标识符。
     */
    public static String requireIdentifier(String raw, String what) {
        String s = raw == null ? "" : raw.trim();
        if (s.length() > 2 && s.startsWith("`") && s.endsWith("`")) {
            s = s.substring(1, s.length() - 1);
        }
        if (!IDENTIFIER.matcher(s).matches()) {
            throw new InvalidArgumentException(
                    "Illegal " + what + " (only [A-Za-z_][A-Za-z0-9_]* is accepted): " + raw,
                    "库名/表名只允许字母、数字和下划线，且必须以字母或下划线开头。"
                            + "先用 list_databases 确认库名（ClickHouse 大小写敏感）。");
        }
        return "`" + s + "`";
    }

    /**
     * 把值转成安全的 SQL 单引号字面量（用于 LIKE 过滤等场景）。
     * ClickHouse 字符串默认支持反斜杠转义，所以两个都要转。
     */
    public static String requireLiteral(String raw, String what) {
        String s = raw == null ? "" : raw;
        if (s.length() > 1024) {
            throw new InvalidArgumentException(
                    "Literal too long for " + what + " (>1024 chars)",
                    "过滤条件过长，请缩短后重试。");
        }
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static String hint(boolean allowWrite, boolean allowDrop) {
        if (!allowWrite) {
            return "服务运行在只读模式：只接受单条只读语句"
                    + "（SELECT / WITH / SHOW / DESCRIBE / EXPLAIN / EXISTS），"
                    + "不能包含 INSERT / CREATE / DROP / SET 等关键字。"
                    + "如确需写入，请管理员开启 clickhouse-mcp.allow-write-access。";
        }
        if (!allowDrop) {
            return "当前允许 SELECT 系与 INSERT / CREATE，"
                    + "但 DROP / TRUNCATE / ALTER / UPDATE / DELETE 被拦截"
                    + "（需管理员开启 clickhouse-mcp.allow-drop）。";
        }
        return "允许单条 SQL（含写与 DDL）。GRANT / SYSTEM / SET / USE 仍被禁止。";
    }

    private static String stripComments(String sql) {
        String noLine = LINE_COMMENT.matcher(sql).replaceAll(" ");
        return BLOCK_COMMENT.matcher(noLine).replaceAll(" ");
    }

    private static String firstWord(String s) {
        int i = 0;
        while (i < s.length() && !Character.isLetter(s.charAt(i))) {
            i++;
        }
        int j = i;
        while (j < s.length() && Character.isLetterOrDigit(s.charAt(j))) {
            j++;
        }
        return s.substring(i, j).toUpperCase(Locale.ROOT);
    }
}
