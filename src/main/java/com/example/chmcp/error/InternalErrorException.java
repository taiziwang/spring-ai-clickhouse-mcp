package com.example.chmcp.error;

/**
 * 兜底分类 —— 上面五类都套不上的意外。
 *
 * <p><b>retryable = false</b>：这不是 Agent 能通过改 SQL 解决的问题，
 * 明确告诉它「别再试了，找管理员」，避免无谓的重试把配额烧光。
 */
public class InternalErrorException extends ClickHouseMcpException {

    public static final String CODE = "InternalError";

    public InternalErrorException(String reason, String hint, Throwable cause) {
        super(CODE, reason, hint, false, cause);
    }
}
