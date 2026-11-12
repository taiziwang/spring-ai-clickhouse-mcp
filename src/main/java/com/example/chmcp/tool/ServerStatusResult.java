package com.example.chmcp.tool;

import com.example.chmcp.query.QueryStats;

import java.util.List;

/**
 * MCP 服务自身的运行状态。
 *
 * @param status          恒为 {@code UP}（能返回这个结果就说明进程活着）
 * @param instanceId      实例标识，形如 {@code <HOSTNAME>#<pid>}。
 *                        <b>多实例部署时用来确认「这次是谁回答的」</b> —— 各实例的
 *                        {@code queryStats} 是各自独立累计的，横向扩容后不要当全局总量
 * @param transport       {@code http} 或 {@code stdio}
 * @param endpoint        HTTP 模式下的 MCP 端点；stdio 模式为 null
 * @param startedAt       JVM 启动时间（ISO-8601）
 * @param uptimeSeconds   已运行秒数
 * @param appVersion      MCP server 版本
 * @param javaVersion     JVM 版本
 * @param threadCount     活跃线程数
 * @param memory          JVM 堆内存
 * @param limits          当前生效的限额配置（确认配置有没有生效时看这个）
 * @param queryStats      自启动以来的 SQL 执行统计
 * @param diagnosis       人话解读
 */
public record ServerStatusResult(
        String status,
        String instanceId,
        String transport,
        String endpoint,
        String startedAt,
        long uptimeSeconds,
        String appVersion,
        String javaVersion,
        int threadCount,
        Memory memory,
        Limits limits,
        QueryStats queryStats,
        List<String> diagnosis) {

    /** @param heapUsedPercent 已用堆占最大堆的百分比 */
    public record Memory(
            long heapUsedMb,
            long heapMaxMb,
            int heapUsedPercent) {
    }

    /**
     * 当前生效的限额配置。{@code accessMode} 取值：
     * {@code READONLY} / {@code WRITE} / {@code WRITE_WITH_DROP}。
     */
    public record Limits(
            int maxRows,
            long maxBytes,
            long queryTimeoutMs,
            int metadataMaxRows,
            String onTruncate,
            int poolSize,
            String accessMode) {
    }
}
