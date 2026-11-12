package com.example.chmcp.query;

import java.util.List;

/**
 * 一次查询的结果。
 *
 * @param columns   列名（已按 {@code getColumnLabel} 取别名）
 * @param rows      行数据；每个值都已由 {@code JdbcValues} 转成可 JSON 化的对象
 *                  （超 JS 安全范围的整数与 DECIMAL 以字符串承载，与 Python 版一致）
 * @param rowCount  实际返回行数（= rows.size()，单独给出方便模型直接读）
 * @param truncated 是否因行数/字节预算而截断。为 true 时结果<b>不是全集</b>，
 *                  模型应据此加过滤条件或聚合，而不是把结果当作完整答案
 * @param elapsedMs 服务端执行耗时（毫秒）
 */
public record QueryResult(
        List<String> columns,
        List<List<Object>> rows,
        int rowCount,
        boolean truncated,
        long elapsedMs) {
}
