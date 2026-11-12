package com.example.chmcp.error;

/**
 * 连不上 ClickHouse —— 服务不可达、连接被拒、连接池借不到连接。
 *
 * <p><b>retryable = true</b>：这是基础设施问题，不是 SQL 问题。
 * Agent 应当稍后原样重试，而<b>不要</b>去改 SQL —— 那只会浪费轮次。
 */
public class DatabaseUnavailableException extends ClickHouseMcpException {

    public static final String CODE = "DatabaseUnavailable";

    public DatabaseUnavailableException(String reason, String hint) {
        this(reason, hint, null);
    }

    public DatabaseUnavailableException(String reason, String hint, Throwable cause) {
        super(CODE, reason, hint, true, cause);
    }
}
