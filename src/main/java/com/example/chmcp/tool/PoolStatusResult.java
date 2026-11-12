package com.example.chmcp.tool;

import java.util.List;

/**
 * HikariCP 连接池的实时状态。
 *
 * @param status                   {@code OK} / {@code FULL} / {@code SATURATED} / {@code NOT_INITIALIZED}
 * @param initialized              连接池是否已启动。<b>懒启动</b>：服务起来后第一次真正访问
 *                                 ClickHouse 才会建池
 * @param activeConnections        正在被借出的连接数
 * @param idleConnections          池中空闲可借的连接数
 * @param totalConnections         池中现有连接总数（active + idle）
 * @param maxPoolSize              池上限
 * @param threadsAwaitingConnection <b>正在排队等借连接的线程数</b> —— 「取数变慢」最关键的指标，
 *                                 大于 0 就说明池已打满、有请求在等
 * @param saturationPercent        active / maxPoolSize 的百分比
 * @param probeOk                  可选探测的结果：true/false；null 表示这次没做探测
 * @param probeElapsedMs           探测耗时（借一条连接 + SELECT 1 + 归还）
 * @param config                   当前生效的连接池配置
 * @param diagnosis                人话解读 —— 排障时真正要看的东西
 */
public record PoolStatusResult(
        String status,
        boolean initialized,
        int activeConnections,
        int idleConnections,
        int totalConnections,
        int maxPoolSize,
        int threadsAwaitingConnection,
        int saturationPercent,
        Boolean probeOk,
        Long probeElapsedMs,
        PoolConfig config,
        List<String> diagnosis) {

    /**
     * 当前生效的连接池配置。{@code jdbcUrl} 保留主机与库名（排障需要知道指向哪个
     * 集群），只抹掉凭据。
     */
    public record PoolConfig(
            int poolSize,
            long connectionTimeoutMs,
            long idleTimeoutMs,
            long maxLifetimeMs,
            String jdbcUrl,
            String driverClassName) {
    }
}
