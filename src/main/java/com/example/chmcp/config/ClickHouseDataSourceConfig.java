package com.example.chmcp.config;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * ClickHouse 数据源。
 *
 * <p>连接方式与 Python 版 mcp-clickhouse 一致：走 ClickHouse 的 <b>HTTP(S) 接口</b>
 * （8123/8443），而不是原生 TCP（9000/9440）—— ClickHouse JDBC 驱动本身就是
 * HTTP 客户端的封装。
 *
 * <h2>只读的服务端强制</h2>
 * 当 {@code allow-write-access=false}（默认）时，JDBC URL 会带
 * {@code custom_http_params=readonly%3D1}，即每个 HTTP 请求都带
 * {@code readonly=1} —— 由 <b>ClickHouse 服务端</b>拒绝一切写操作与 SET。
 * 客户端的 SqlGuard 只是提前拦截省一次往返；真正的安全边界在服务端。
 * 这与 Python 版 {@code CLICKHOUSE_ALLOW_WRITE_ACCESS=false → readonly=1} 的行为一致。
 *
 * <h2>为什么是连接池</h2>
 * ClickHouse JDBC 的 {@code Connection} 不是线程安全的；多个 Agent 并发共用一条
 * 连接会串结果。池化后连接用完即还，占用只与并发度有关，与 Agent 数量无关。
 */
@Configuration
@EnableConfigurationProperties(ClickHouseProperties.class)
public class ClickHouseDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseDataSourceConfig.class);

    /**
     * 用 {@code new HikariDataSource()} 无参构造 + setter，池是<b>延迟启动</b>的
     * —— 第一次 {@code getConnection()} 才真正建池建连。
     * ClickHouse 不可达不应该让网关进程起不来。
     */
    @Bean(destroyMethod = "close")
    public HikariDataSource clickHouseDataSource(ClickHouseProperties props) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("clickhouse-pool");
        ds.setJdbcUrl(buildJdbcUrl(props));
        ds.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        if (StringUtils.hasText(props.getUsername())) {
            ds.setUsername(props.getUsername().trim());
        }
        if (StringUtils.hasText(props.getPassword())) {
            ds.setPassword(props.getPassword());
        }

        ds.setMaximumPoolSize(props.getPoolSize());
        ds.setMinimumIdle(0);
        ds.setConnectionTimeout(props.getConnectionTimeoutMs());
        ds.setIdleTimeout(props.getIdleTimeoutMs());
        ds.setMaxLifetime(props.getMaxLifetimeMs());
        ds.setAutoCommit(true);

        log.info("ClickHouse 数据源已配置: host={}, port={}, secure={}, verify={}, database={}, "
                        + "readonly={}, allowWrite={}, allowDrop={}, poolSize={}, queryTimeout={}ms, "
                        + "maxRows={}, maxBytes={}",
                props.getHost(), props.effectivePort(), props.isSecure(), props.isVerify(),
                props.getDatabase(), !props.isAllowWriteAccess(), props.isAllowWriteAccess(),
                props.isAllowDrop(), props.getPoolSize(), props.getQueryTimeoutMs(),
                props.getMaxRows(), props.getMaxBytes());
        return ds;
    }

    /**
     * 拼 JDBC URL。形如：
     * <pre>
     *   jdbc:clickhouse://host:8443/default?ssl=true&amp;sslmode=strict
     *       &amp;connect_timeout=30000&amp;socket_timeout=300000
     *       &amp;custom_http_params=readonly%3D1
     * </pre>
     * <ul>
     *   <li>{@code ssl}/{@code sslmode} —— 对应 CLICKHOUSE_SECURE / CLICKHOUSE_VERIFY；
     *       {@code sslmode=none} 即跳过证书校验（自签证书场景）。</li>
     *   <li>{@code connection_timeout} / {@code socket_timeout} —— 驱动 v2 的参数名（毫秒）。
     *       ⚠️ v1 的 {@code connect_timeout} 在 0.10 会直接拒绝启动连接，
     *       而外层异常只有 "Failed to create connection"，看不出根因。</li>
     *   <li>{@code custom_http_params=readonly%3D1} —— 只读模式下让服务端强制只读。
     *       值必须 URL 编码（readonly=1 → readonly%3D1），否则会被当成 JDBC URL
     *       自己的参数分隔符解析坏。</li>
     * </ul>
     */
    static String buildJdbcUrl(ClickHouseProperties props) {
        StringBuilder url = new StringBuilder("jdbc:clickhouse://")
                .append(props.getHost().trim())
                .append(':')
                .append(props.effectivePort())
                .append('/')
                .append(props.getDatabase().trim());

        StringBuilder query = new StringBuilder();
        if (props.isSecure()) {
            query.append("&ssl=true");
            query.append(props.isVerify() ? "&sslmode=strict" : "&sslmode=none");
        }
        // ⚠️ 参数名必须是 connection_timeout / socket_timeout（v2 驱动）。
        // v1 时代的 connect_timeout 在 0.10 会被拒：
        //   ClientMisconfigurationException: Unknown and unmapped config properties: [connect_timeout]
        // 而外层只看到 SQLException("Failed to create connection")，根因极易被误判成网络问题。
        query.append("&connection_timeout=").append(props.getConnectTimeoutMs());
        query.append("&socket_timeout=").append(props.getSocketTimeoutMs());

        if (!props.isAllowWriteAccess()) {
            // 服务端强制只读：等价 Python 版的 readonly=1
            query.append("&custom_http_params=")
                    .append(URLEncoder.encode("readonly=1", StandardCharsets.UTF_8));
        }

        if (query.length() > 0) {
            query.setCharAt(0, '?');
            url.append(query);
        }
        return url.toString();
    }
}
