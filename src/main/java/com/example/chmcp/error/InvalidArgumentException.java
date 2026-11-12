package com.example.chmcp.error;

/**
 * 参数或 SQL 本身有问题 —— 语法错、表名错、多语句、非法标识符。
 *
 * <p><b>retryable = true</b>：Agent 改一版 SQL 再试是有意义的，这是最常见的可自愈错误。
 */
public class InvalidArgumentException extends ClickHouseMcpException {

    public static final String CODE = "InvalidArgument";

    public InvalidArgumentException(String reason, String hint) {
        this(reason, hint, null);
    }

    public InvalidArgumentException(String reason, String hint, Throwable cause) {
        super(CODE, reason, hint, true, cause);
    }
}
