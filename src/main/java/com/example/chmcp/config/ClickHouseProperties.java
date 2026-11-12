package com.example.chmcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 受控配置面。
 *
 * <p><b>原则：连接信息只来自服务端配置，永不接受工具参数传入。</b>
 * 否则模型可以指定任意主机/账号，等于把 SSRF 入口交给模型。
 *
 * <p>配置项与 Python 版 mcp-clickhouse 的环境变量对应：
 * <pre>
 *   CLICKHOUSE_HOST                   → clickhouse-mcp.host
 *   CLICKHOUSE_PORT                   → clickhouse-mcp.port
 *   CLICKHOUSE_SECURE                 → clickhouse-mcp.secure
 *   CLICKHOUSE_VERIFY                 → clickhouse-mcp.verify
 *   CLICKHOUSE_USER                   → clickhouse-mcp.username
 *   CLICKHOUSE_PASSWORD               → clickhouse-mcp.password
 *   CLICKHOUSE_DATABASE               → clickhouse-mcp.database
 *   CLICKHOUSE_CONNECT_TIMEOUT        → clickhouse-mcp.connect-timeout-ms（秒→毫秒）
 *   CLICKHOUSE_SEND_RECEIVE_TIMEOUT   → clickhouse-mcp.socket-timeout-ms
 *   CLICKHOUSE_ALLOW_WRITE_ACCESS     → clickhouse-mcp.allow-write-access
 *   CLICKHOUSE_ALLOW_DROP             → clickhouse-mcp.allow-drop
 *   CLICKHOUSE_MCP_QUERY_TIMEOUT      → clickhouse-mcp.query-timeout-ms
 * </pre>
 * Spring Boot 宽松绑定会把 {@code CLICKHOUSE_MCP_PASSWORD} 映射到
 * {@code clickhouse-mcp.password}，口令用环境变量注入，不要写进配置文件。
 */
@ConfigurationProperties(prefix = "clickhouse-mcp")
public class ClickHouseProperties {

    /** ClickHouse 主机。对应 CLICKHOUSE_HOST。 */
    private String host = "localhost";

    /**
     * HTTP(S) 端口。0 表示按 {@link #secure} 自动选：secure=true → 8443，否则 8123。
     * ⚠️ 必须是 HTTP 端口，不能用原生 TCP 的 9000/9440（与 Python 版一致）。
     */
    private int port = 0;

    /** 是否走 HTTPS。对应 CLICKHOUSE_SECURE，Python 版默认 true。 */
    private boolean secure = true;

    /** 是否校验服务端证书。对应 CLICKHOUSE_VERIFY。 */
    private boolean verify = true;

    /** 账号。对应 CLICKHOUSE_USER。 */
    private String username = "default";

    /** 口令。建议用环境变量 CLICKHOUSE_MCP_PASSWORD 注入。 */
    private String password = "";

    /** 默认数据库。对应 CLICKHOUSE_DATABASE。 */
    private String database = "default";

    /** 建连超时。对应 CLICKHOUSE_CONNECT_TIMEOUT（Python 版默认 30 秒）。 */
    private long connectTimeoutMs = 30_000;

    /** 读写超时。对应 CLICKHOUSE_SEND_RECEIVE_TIMEOUT（Python 版默认 300 秒）。 */
    private long socketTimeoutMs = 300_000;

    /**
     * 是否放行写操作（INSERT / CREATE）。对应 CLICKHOUSE_ALLOW_WRITE_ACCESS。
     *
     * <p>默认 false，此时 JDBC URL 会带 {@code readonly=1} —— 由 ClickHouse
     * <b>服务端</b>强制只读，客户端守卫（SqlGuard）只是省一次往返的体验优化。
     * 这与 Python 版的设计一致：真正的安全边界是 ClickHouse 侧的用户权限，
     * 客户端开关只是防误操作。
     */
    private boolean allowWriteAccess = false;

    /**
     * 是否放行危险操作（DROP / TRUNCATE / DELETE / UPDATE / ALTER 等）。
     * 对应 CLICKHOUSE_ALLOW_DROP。默认 false。
     * ⚠️ 只在 {@link #allowWriteAccess}=true 时才有意义。
     */
    private boolean allowDrop = false;

    // ============================== 连接池 ==============================

    /** 连接池上限。 */
    private int poolSize = 5;

    /** 从池里借连接的超时。 */
    private long connectionTimeoutMs = 10_000;

    /** 空闲连接回收时间。 */
    private long idleTimeoutMs = 600_000;

    /** 连接最长存活时间。 */
    private long maxLifetimeMs = 1_800_000;

    // ============================== 查询限额 ==============================

    /**
     * 单次查询的墙钟超时，到点会真正 {@code Statement.cancel()}。
     * 对应 CLICKHOUSE_MCP_QUERY_TIMEOUT（Python 版默认 30 秒）。
     */
    private long queryTimeoutMs = 30_000;

    /** JDBC fetchSize。 */
    private int fetchSize = 1000;

    /** 结果行数预算。 */
    private int maxRows = 5000;

    /** 结果字节预算（估算值）。 */
    private long maxBytes = 4L * 1024 * 1024;

    /** 元数据类工具（list_databases / list_tables）的行数预算。 */
    private int metadataMaxRows = 2000;

    /**
     * 结果被行数/字节预算截断时的行为。
     *
     * <ul>
     *   <li>{@code ERROR}（默认）—— 抛 QueryLimitExceeded，不返回任何数据。
     *       强制 Agent 感知结果不完整。</li>
     *   <li>{@code PARTIAL} —— 正常返回部分数据，并置 {@code truncated=true}。</li>
     * </ul>
     *
     * <p>默认选 ERROR 的理由是不对称性：截断结果被当成全集会产出
     * <b>自信但错误的结论且无人察觉</b>，而报错只多花一次往返。
     * 只对 run_query 生效；元数据工具被截断不算失败。
     */
    private TruncatePolicy onTruncate = TruncatePolicy.ERROR;

    public enum TruncatePolicy {
        PARTIAL,
        ERROR
    }

    /** 运维诊断工具的开关。默认开启 —— 排障时最忌讳「要先改配置重启才能看」。 */
    private final Diagnostics diagnostics = new Diagnostics();

    public static class Diagnostics {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    // ============================== getter/setter ==============================

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    /** 生效端口：显式配置优先，否则按 secure 推导。 */
    public int effectivePort() {
        return port > 0 ? port : (secure ? 8443 : 8123);
    }

    public boolean isSecure() {
        return secure;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    public boolean isVerify() {
        return verify;
    }

    public void setVerify(boolean verify) {
        this.verify = verify;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getSocketTimeoutMs() {
        return socketTimeoutMs;
    }

    public void setSocketTimeoutMs(long socketTimeoutMs) {
        this.socketTimeoutMs = socketTimeoutMs;
    }

    public boolean isAllowWriteAccess() {
        return allowWriteAccess;
    }

    public void setAllowWriteAccess(boolean allowWriteAccess) {
        this.allowWriteAccess = allowWriteAccess;
    }

    public boolean isAllowDrop() {
        return allowDrop;
    }

    public void setAllowDrop(boolean allowDrop) {
        this.allowDrop = allowDrop;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    public long getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(long connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    public void setIdleTimeoutMs(long idleTimeoutMs) {
        this.idleTimeoutMs = idleTimeoutMs;
    }

    public long getMaxLifetimeMs() {
        return maxLifetimeMs;
    }

    public void setMaxLifetimeMs(long maxLifetimeMs) {
        this.maxLifetimeMs = maxLifetimeMs;
    }

    public long getQueryTimeoutMs() {
        return queryTimeoutMs;
    }

    public void setQueryTimeoutMs(long queryTimeoutMs) {
        this.queryTimeoutMs = queryTimeoutMs;
    }

    public int getFetchSize() {
        return fetchSize;
    }

    public void setFetchSize(int fetchSize) {
        this.fetchSize = fetchSize;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public int getMetadataMaxRows() {
        return metadataMaxRows;
    }

    public void setMetadataMaxRows(int metadataMaxRows) {
        this.metadataMaxRows = metadataMaxRows;
    }

    public TruncatePolicy getOnTruncate() {
        return onTruncate;
    }

    public void setOnTruncate(TruncatePolicy onTruncate) {
        this.onTruncate = onTruncate;
    }

    public Diagnostics getDiagnostics() {
        return diagnostics;
    }
}
