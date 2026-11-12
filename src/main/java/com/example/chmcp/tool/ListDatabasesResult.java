package com.example.chmcp.tool;

import java.util.List;

/**
 * @param databases 数据库名列表
 * @param truncated 是否因行数预算被截断
 */
public record ListDatabasesResult(List<String> databases, boolean truncated) {
}
