package com.example.chmcp.error;

/**
 * 本服务所有「可预期失败」的基类。
 *
 * <h2>为什么用异常，而不是返回错误对象</h2>
 * Spring AI 2.0 的 {@code McpToolUtils} 捕获工具方法抛出的异常后会构造
 * {@code CallToolResult.builder().content(TextContent(e.getMessage())).isError(true)}
 * —— <b>isError=true 是框架自动置的</b>，比我们自己拼一个「带 error 字段的成功结果」
 * 更符合 MCP 语义。消息不会被改写：{@code MethodToolCallback} 包装成
 * {@code ToolExecutionException} 时原样透传 {@code cause.getMessage()}。
 *
 * <h2>消息为什么是 JSON</h2>
 * 框架只透传 {@code getMessage()}，结构化字段不会自动带上，所以分类信息
 * 编码成紧凑 JSON，与成功返回的 payload 形态一致：
 * <pre>
 * {"code":"InvalidArgument","message":"...","hint":"...","retryable":true}
 * </pre>
 * <ul>
 *   <li>{@code code} —— 稳定分类标识，Agent 可据此分支</li>
 *   <li>{@code message} —— 出了什么事（已抹掉连接串与口令）</li>
 *   <li>{@code hint} —— 下一步该怎么做，这是最有价值的字段</li>
 *   <li>{@code retryable} —— 改一下再试是否有意义</li>
 * </ul>
 */
public abstract class ClickHouseMcpException extends RuntimeException {

    private final String code;
    private final String reason;
    private final String hint;
    private final boolean retryable;

    protected ClickHouseMcpException(String code, String reason, String hint,
                                     boolean retryable, Throwable cause) {
        super(payload(code, reason, hint, retryable), cause);
        this.code = code;
        this.reason = reason;
        this.hint = hint;
        this.retryable = retryable;
    }

    /** 稳定分类标识。 */
    public String code() {
        return code;
    }

    /** 原始原因文本（不含 JSON 包装），日志与单测用。 */
    public String reason() {
        return reason;
    }

    /** 下一步建议。 */
    public String hint() {
        return hint;
    }

    /** 重试是否有意义。 */
    public boolean retryable() {
        return retryable;
    }

    /** 给人看的单行形式 —— 消息本身是 JSON，日志里读起来累。 */
    public String readable() {
        return "[" + code + "] " + reason
                + (hint == null || hint.isBlank() ? "" : " | hint: " + hint)
                + " | retryable=" + retryable;
    }

    private static String payload(String code, String reason, String hint, boolean retryable) {
        return "{\"code\":" + quote(code)
                + ",\"message\":" + quote(reason == null ? "" : reason)
                + ",\"hint\":" + quote(hint == null ? "" : hint)
                + ",\"retryable\":" + retryable
                + "}";
    }

    /**
     * 极简 JSON 字符串转义。刻意不引 Jackson：异常构造路径要轻、要确定。
     */
    static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
