package com.example.chmcp;

import com.example.chmcp.error.AccessDeniedException;
import com.example.chmcp.error.InvalidArgumentException;
import com.example.chmcp.sql.SqlGuard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 钉死 SQL 守卫的三级行为，防止静默回归。
 *
 * <p>两类异常的语义是刻意分开的（决定 Agent 要不要重试）：
 * <ul>
 *   <li>{@link AccessDeniedException} —— 被访问级别/策略拦下，改 SQL 没用</li>
 *   <li>{@link InvalidArgumentException} —— SQL 本身有问题，改一版可重试</li>
 * </ul>
 */
class SqlGuardTest {

    // ---------- 只读模式：放行 ----------

    @Test
    void readonlyAllowsSelectFamily() {
        assertEquals("SELECT 1", SqlGuard.validate("SELECT 1", false, false));
        assertEquals("WITH t AS (SELECT 1) SELECT * FROM t",
                SqlGuard.validate("WITH t AS (SELECT 1) SELECT * FROM t", false, false));
        assertEquals("SHOW TABLES", SqlGuard.validate("SHOW TABLES", false, false));
        assertEquals("DESCRIBE events", SqlGuard.validate("DESCRIBE events", false, false));
        assertEquals("EXPLAIN ESTIMATE SELECT 1",
                SqlGuard.validate("EXPLAIN ESTIMATE SELECT 1", false, false));
        assertEquals("EXISTS TABLE events",
                SqlGuard.validate("EXISTS TABLE events", false, false));
    }

    @Test
    void metadataStatementsMayContainWriteKeywords() {
        // SHOW CREATE TABLE 是只读元数据语句，语句体里的 CREATE 不能误杀
        assertEquals("SHOW CREATE TABLE demo.events",
                SqlGuard.validate("SHOW CREATE TABLE demo.events", false, false));
        assertEquals("SHOW CREATE DATABASE demo",
                SqlGuard.validate("SHOW CREATE DATABASE demo", false, false));
        // EXPLAIN 不享受这个豁免：EXPLAIN INSERT 会被拦（给 Agent 错误暗示）
        assertThrows(AccessDeniedException.class,
                () -> SqlGuard.validate("EXPLAIN INSERT INTO t VALUES (1)", false, false));
    }

    @Test
    void queryScopedSettingsClauseAllowed() {
        // SELECT ... SETTINGS 是语句级设置，不改变会话状态，也不匹配 \bSET\b ——
        // 放行它是有意的：Agent 常用它给单条查询限流
        assertEquals("SELECT * FROM t SETTINGS max_threads=1",
                SqlGuard.validate("SELECT * FROM t SETTINGS max_threads=1", false, false));
    }

    // ---------- 只读模式：拦截（不可重试） ----------

    @Test
    void readonlyRejectsWrites() {
        assertThrows(AccessDeniedException.class,
                () -> SqlGuard.validate("INSERT INTO t VALUES (1)", false, false));
        assertThrows(AccessDeniedException.class,
                () -> SqlGuard.validate("CREATE TABLE t (x Int32) ENGINE = Memory", false, false));
        assertThrows(AccessDeniedException.class,
                () -> SqlGuard.validate("DROP TABLE t", false, false));
        assertThrows(AccessDeniedException.class,
                () -> SqlGuard.validate("ALTER TABLE t DELETE WHERE x = 1", false, false));
        // 写关键字出现在语句中间也拦（哪怕在字符串字面量里 —— 误杀优于漏放）
        assertThrows(AccessDeniedException.class,
                () -> SqlGuard.validate("SELECT 'DROP TABLE t'", false, false));
    }

    @Test
    void alwaysForbiddenEvenWhenWriteEnabled() {
        // SET 语句被禁：会污染池化连接的会话状态，影响下一位借用者
        assertThrows(AccessDeniedException.class,
                () -> SqlGuard.validate("SET max_execution_time = 60", true, true));
        assertThrows(AccessDeniedException.class,
                () -> SqlGuard.validate("GRANT SELECT ON t TO u", true, true));
        assertThrows(AccessDeniedException.class,
                () -> SqlGuard.validate("SYSTEM FLUSH LOGS", true, true));
        assertThrows(AccessDeniedException.class,
                () -> SqlGuard.validate("SELECT * INTO OUTFILE '/tmp/x' FROM t", true, true));
    }

    // ---------- 写模式 ----------

    @Test
    void writeModeAllowsInsertAndCreate() {
        assertEquals("INSERT INTO t VALUES (1)",
                SqlGuard.validate("INSERT INTO t VALUES (1)", true, false));
        assertEquals("CREATE TABLE t (x Int32) ENGINE = Memory",
                SqlGuard.validate("CREATE TABLE t (x Int32) ENGINE = Memory", true, false));
    }

    @Test
    void writeModeStillBlocksDropFamily() {
        assertThrows(AccessDeniedException.class,
                () -> SqlGuard.validate("DROP TABLE t", true, false));
        assertThrows(AccessDeniedException.class,
                () -> SqlGuard.validate("TRUNCATE TABLE t", true, false));
        assertThrows(AccessDeniedException.class,
                () -> SqlGuard.validate("DELETE FROM t WHERE x = 1", true, false));
        assertThrows(AccessDeniedException.class,
                () -> SqlGuard.validate("ALTER TABLE t UPDATE x = 1 WHERE true", true, false));
    }

    // ---------- 危险模式 ----------

    @Test
    void dropModeAllowsDangerousOps() {
        assertEquals("DROP TABLE t", SqlGuard.validate("DROP TABLE t", true, true));
        assertEquals("TRUNCATE TABLE t", SqlGuard.validate("TRUNCATE TABLE t", true, true));
        // ClickHouse 的轻量删除是独立语句，首词 DELETE
        assertEquals("DELETE FROM t WHERE x = 1",
                SqlGuard.validate("DELETE FROM t WHERE x = 1", true, true));
        assertEquals("ALTER TABLE t UPDATE x = 1 WHERE true",
                SqlGuard.validate("ALTER TABLE t UPDATE x = 1 WHERE true", true, true));
    }

    // ---------- 语法层：可重试 ----------

    @Test
    void typoIsRetryableNotAccessDenied() {
        // 拼写错误不是权限问题：Agent 改一版就能重试，不能报成 AccessDenied
        assertThrows(InvalidArgumentException.class,
                () -> SqlGuard.validate("SELEC 1", false, false));
        assertThrows(InvalidArgumentException.class,
                () -> SqlGuard.validate("SELEC * FROM demo.events", false, false));
    }

    @Test
    void multiStatementRejected() {
        assertThrows(InvalidArgumentException.class,
                () -> SqlGuard.validate("SELECT 1; SELECT 2", false, false));
    }

    @Test
    void emptySqlRejected() {
        assertThrows(InvalidArgumentException.class, () -> SqlGuard.validate("   ", false, false));
        assertThrows(InvalidArgumentException.class, () -> SqlGuard.validate(null, false, false));
    }

    @Test
    void trailingSemicolonStripped() {
        assertEquals("SELECT 1", SqlGuard.validate("SELECT 1;", false, false));
    }

    // ---------- 标识符与字面量 ----------

    @Test
    void identifierValidation() {
        assertEquals("`events`", SqlGuard.requireIdentifier("events", "table name"));
        assertEquals("`events`", SqlGuard.requireIdentifier("`events`", "table name"));
        assertThrows(InvalidArgumentException.class,
                () -> SqlGuard.requireIdentifier("events; DROP TABLE x", "table name"));
        assertThrows(InvalidArgumentException.class,
                () -> SqlGuard.requireIdentifier("1abc", "table name"));
        assertThrows(InvalidArgumentException.class,
                () -> SqlGuard.requireIdentifier("a b", "table name"));
    }

    @Test
    void literalEscaping() {
        assertEquals("'events_%'", SqlGuard.requireLiteral("events_%", "like"));
        assertEquals("'it\\'s'", SqlGuard.requireLiteral("it's", "like"));
        assertEquals("'a\\\\b'", SqlGuard.requireLiteral("a\\b", "like"));
    }
}
