package com.example.chmcp.tool;

import java.util.List;

/**
 * 一张表的元数据（来自 system.tables，列详情来自 system.columns）。
 *
 * @param database          所属库
 * @param name              表名
 * @param engine            表引擎（MergeTree / Distributed / View ...）
 * @param comment           表注释
 * @param createTableQuery  建表语句
 * @param dependencies      该表依赖的 db.table 列表（物化视图等场景有用）
 * @param columns           列详情；仅在 list_tables 传 include_detailed_columns=true 时有值，
 *                          否则为 null（不是空列表 —— null 明确表达「没查」，空列表是「查过但没列」）
 */
public record TableInfo(
        String database,
        String name,
        String engine,
        String comment,
        String createTableQuery,
        List<String> dependencies,
        List<ColumnInfo> columns) {

    /**
     * 一列的元数据。
     *
     * @param name              列名
     * @param type              ClickHouse 类型（如 {@code Nullable(String)}）
     * @param defaultKind       默认值种类（DEFAULT / MATERIALIZED / ALIAS），无则空串
     * @param defaultExpression 默认值表达式，无则空串
     * @param comment           列注释
     */
    public record ColumnInfo(
            String name,
            String type,
            String defaultKind,
            String defaultExpression,
            String comment) {
    }
}
