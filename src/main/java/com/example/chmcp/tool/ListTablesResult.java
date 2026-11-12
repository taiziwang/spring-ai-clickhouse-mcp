package com.example.chmcp.tool;

import java.util.List;

/**
 * list_tables 的结果。
 *
 * @param database      实际查询的库名
 * @param tables        表元数据列表
 * @param nextPageToken 还有更多页时给出的翻页令牌，原样传回 list_tables 的 page_token
 *                      即可取下一页；null 表示已到末页
 * @param truncated     是否因行数预算被截断。为 true 时清单不完整，
 *                      不能据此判断「表不存在」
 */
public record ListTablesResult(
        String database,
        List<TableInfo> tables,
        String nextPageToken,
        boolean truncated) {
}
