package com.example.chmcp.error;

/**
 * 权限不足 / 只读模式拦截 —— 该账号无权访问目标库表，或服务端强制只读。
 *
 * <p>ClickHouse 侧对应：Code 516（认证失败）、Code 497（权限不足）、
 * Code 164（readonly 模式下执行了写操作）。
 *
 * <p><b>retryable = false</b>：这是服务端授权/只读配置决定的，
 * 换个 SQL 写法也过不去。明确告诉 Agent 别浪费轮次重试。
 */
public class AccessDeniedException extends ClickHouseMcpException {

    public static final String CODE = "AccessDenied";

    public AccessDeniedException(String reason, String hint) {
        this(reason, hint, null);
    }

    public AccessDeniedException(String reason, String hint, Throwable cause) {
        super(CODE, reason, hint, false, cause);
    }
}
