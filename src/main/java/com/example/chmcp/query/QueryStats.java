package com.example.chmcp.query;

import java.util.Map;

/**
 * 自进程启动以来累计的 SQL 执行统计。
 *
 * <p>给运维看的：一眼判断「最近失败集中在哪一类」，比翻日志快得多。
 * 计数在进程内累计，<b>重启即清零</b> —— 这是刻意的，避免引入持久化依赖。
 *
 * @param total          总执行次数（含元数据语句）
 * @param succeeded      成功次数
 * @param failed         失败次数（抛了 ClickHouseMcpException 的都算）
 * @param truncated      结果被预算截断的次数
 * @param failuresByCode 按错误分类码聚合的失败次数，如 {"QueryTimeout": 3}
 */
public record QueryStats(
        long total,
        long succeeded,
        long failed,
        long truncated,
        Map<String, Long> failuresByCode) {
}
