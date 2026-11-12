package com.example.chmcp.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 JDBC 值安全转换为可序列化的 Java 对象。
 *
 * <p>两处与 Python 版 mcp-clickhouse 对齐的行为：
 * <ul>
 *   <li><b>超出 JS 安全范围的整数</b>（|v| > 2^53-1）以十进制字符串返回 ——
 *       ClickHouse 的 UInt64 / Int128 / Int256 很容易越界，JSON 化成 number
 *       会静默丢精度。</li>
 *   <li><b>DECIMAL 保留精度</b>：以字符串承载，避免浮点化。</li>
 * </ul>
 */
public final class JdbcValues {

    private static final int MAX_STRING_CHARS = 65536;

    /** JS 安全整数边界：2^53 - 1。 */
    private static final long JS_SAFE_MAX = 9007199254740991L;
    private static final long JS_SAFE_MIN = -9007199254740991L;

    private static final BigInteger JS_SAFE_MAX_BI = BigInteger.valueOf(JS_SAFE_MAX);
    private static final BigInteger JS_SAFE_MIN_BI = BigInteger.valueOf(JS_SAFE_MIN);

    private JdbcValues() {}

    public static List<Object> readRow(ResultSet rs, ResultSetMetaData md) throws SQLException {
        int n = md.getColumnCount();
        List<Object> row = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            row.add(convert(rs, i, md.getColumnType(i)));
        }
        return row;
    }

    private static Object convert(ResultSet rs, int i, int type) {
        try {
            return switch (type) {
                case Types.DECIMAL, Types.NUMERIC -> {
                    BigDecimal d = rs.getBigDecimal(i);
                    if (d == null) {
                        yield null;
                    }
                    BigDecimal stripped = d.stripTrailingZeros();
                    if (stripped.scale() <= 0) {
                        // 整数型 DECIMAL：ClickHouse 的 UInt64 / Int128 / Int256 会被驱动
                        // 映射成 NUMERIC，count() 这类结果也走这里。
                        // 安全范围内必须返回数值 —— 否则 count() 会变成 "200" 字符串，
                        // Agent 拿去做比较/排序会出问题。
                        BigInteger bi = stripped.toBigIntegerExact();
                        yield (bi.compareTo(JS_SAFE_MAX_BI) <= 0 && bi.compareTo(JS_SAFE_MIN_BI) >= 0)
                                ? (Object) bi.longValue()
                                : bi.toString();
                    }
                    // 带小数：字符串保精度，避免浮点化丢位
                    yield stripped.toPlainString();
                }
                case Types.BIGINT -> {
                    // Int64：在 JS 安全范围内就给 long，越界转字符串
                    long v = rs.getLong(i);
                    if (rs.wasNull()) {
                        yield null;
                    }
                    yield (v >= JS_SAFE_MIN && v <= JS_SAFE_MAX) ? v : Long.toString(v);
                }
                case Types.DATE -> {
                    Date d = rs.getDate(i);
                    yield d == null ? null : d.toString();            // yyyy-MM-dd，不含时区
                }
                case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> {
                    Timestamp t = rs.getTimestamp(i);
                    yield t == null ? null : t.toInstant().toString(); // 统一为 UTC ISO-8601
                }
                case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> {
                    byte[] b = rs.getBytes(i);
                    yield b == null ? null : "[binary " + b.length + " bytes]";
                }
                default -> {
                    Object v = rs.getObject(i);
                    if (v == null) {
                        yield null;
                    }
                    // 数值/布尔原样返回 —— 否则 UInt16/Int32/Float64 这类
                    // 走不到上面 case 的类型会被 toString 成 "20"，
                    // Agent 拿去做比较/排序就错了。
                    if (v instanceof Number || v instanceof Boolean) {
                        yield v;
                    }
                    // 大整数（UInt128/Int256 等）以字符串返回，避免 JSON 丢精度
                    if (v instanceof BigInteger bi) {
                        yield bi.toString();
                    }
                    // Array / Map / Tuple / Nested 等复杂类型由驱动的 toString 表示，
                    // 超长字段截断，避免单行撑爆结果预算
                    String s = v.toString();
                    yield s.length() > MAX_STRING_CHARS
                            ? s.substring(0, MAX_STRING_CHARS) + "...[truncated]"
                            : s;
                }
            };
        } catch (Exception e) {
            // 单列读失败不应让整行丢失
            return "[unreadable: " + e.getClass().getSimpleName() + "]";
        }
    }

    /** 粗略行大小估算，用于字节数预算。 */
    public static long estimateBytes(List<Object> row) {
        long total = 32;  // 列表开销
        for (Object v : row) {
            if (v == null) {
                total += 8;
            } else if (v instanceof String s) {
                total += 16L + 2L * s.length();
            } else {
                total += 32L;
            }
        }
        return total;
    }
}
