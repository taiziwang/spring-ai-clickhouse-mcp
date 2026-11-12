package com.example.chmcp.error;

/**
 * 结果集超出行数/字节预算。
 *
 * <p>触发条件：{@code clickhouse-mcp.on-truncate=ERROR}（<b>默认</b>）时，
 * 结果超预算就抛这个异常，不返回任何数据。
 * 若改成 {@code PARTIAL}，超限则是<b>正常返回部分数据</b>并置
 * {@code truncated=true}，不抛异常。
 *
 * <p><b>retryable = true</b>：改成聚合查询或加过滤条件，再试。
 */
public class QueryLimitExceededException extends ClickHouseMcpException {

    public static final String CODE = "QueryLimitExceeded";

    public QueryLimitExceededException(String reason, String hint) {
        this(reason, hint, null);
    }

    public QueryLimitExceededException(String reason, String hint, Throwable cause) {
        super(CODE, reason, hint, true, cause);
    }
}
