package com.example.chmcp.error;

/**
 * 查询超出时间预算，已被看门狗线程 {@code Statement.cancel()} 中止。
 * ClickHouse 侧对应 Code 160（超过 max_execution_time）。
 *
 * <p><b>retryable = true</b>：加 LIMIT / 缩小时间范围 / 先聚合，再试。
 */
public class QueryTimeoutException extends ClickHouseMcpException {

    public static final String CODE = "QueryTimeout";

    public QueryTimeoutException(String reason, String hint) {
        this(reason, hint, null);
    }

    public QueryTimeoutException(String reason, String hint, Throwable cause) {
        super(CODE, reason, hint, true, cause);
    }
}
