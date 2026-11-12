package com.example.chmcp;

import com.example.chmcp.error.AccessDeniedException;
import com.example.chmcp.error.ClickHouseErrorClassifier;
import com.example.chmcp.error.ClickHouseMcpException;
import com.example.chmcp.error.DatabaseUnavailableException;
import com.example.chmcp.error.InternalErrorException;
import com.example.chmcp.error.InvalidArgumentException;
import com.example.chmcp.error.QueryTimeoutException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉死错误分类规则。分类是 Agent 决定「下一步怎么办」的依据，
 * 规则错一个，Agent 的行为就会错（比如把借不到连接当成查询超时去改 SQL）。
 */
class ClickHouseErrorClassifierTest {

    private static SQLException sqlErr(String msg) {
        return new SQLException(msg);
    }

    @Test
    void connectionRefusedIsUnavailable() {
        assertInstanceOf(DatabaseUnavailableException.class,
                ClickHouseErrorClassifier.classify(sqlErr("Connection refused: connect")));
    }

    @Test
    void hikariPoolExhaustedIsUnavailableNotTimeout() {
        // HikariCP 借不到连接的报错同时含 "timed out"，
        // 必须先判连接类，不能误报成查询超时
        assertInstanceOf(DatabaseUnavailableException.class,
                ClickHouseErrorClassifier.classify(
                        new SQLTransientConnectionException(
                                "Connection is not available, request timed out after 10000ms")));
    }

    @Test
    void unknownTableIsInvalidArgument() {
        assertInstanceOf(InvalidArgumentException.class,
                ClickHouseErrorClassifier.classify(
                        sqlErr("Code: 60. DB::Exception: Table default.no_such doesn't exist.")));
    }

    @Test
    void syntaxErrorIsInvalidArgument() {
        assertInstanceOf(InvalidArgumentException.class,
                ClickHouseErrorClassifier.classify(
                        sqlErr("Code: 62. DB::Exception: Syntax error: failed at position 10")));
    }

    @Test
    void authFailedIsAccessDenied() {
        assertInstanceOf(AccessDeniedException.class,
                ClickHouseErrorClassifier.classify(
                        sqlErr("Code: 516. DB::Exception: default: Authentication failed")));
    }

    @Test
    void readonlyViolationIsAccessDenied() {
        ClickHouseMcpException e = ClickHouseErrorClassifier.classify(
                sqlErr("Code: 164. DB::Exception: Cannot execute query in readonly mode"));
        assertInstanceOf(AccessDeniedException.class, e);
        assertTrue(e.hint().contains("allow-write-access"),
                "readonly 拦截的 hint 应指向写开关");
    }

    @Test
    void timeoutExceededIsQueryTimeout() {
        assertInstanceOf(QueryTimeoutException.class,
                ClickHouseErrorClassifier.classify(
                        sqlErr("Code: 160. DB::Exception: Timeout exceeded: elapsed 30.0 seconds")));
        assertInstanceOf(QueryTimeoutException.class,
                ClickHouseErrorClassifier.classify(new SQLTimeoutException("timed out")));
    }

    @Test
    void unknownFallsBackToInternal() {
        assertInstanceOf(InternalErrorException.class,
                ClickHouseErrorClassifier.classify(sqlErr("Code: 9999. Something totally weird")));
    }

    @Test
    void knownExceptionPassesThrough() {
        InvalidArgumentException original = new InvalidArgumentException("bad sql", "fix it");
        assertEquals(original,
                ClickHouseErrorClassifier.classify(new RuntimeException("wrapped", original)));
    }

    @Test
    void sanitizeStripsCredentials() {
        // 整个 JDBC URL 会被抹成 jdbc:clickhouse://***（异常文本会把连接串带进模型上下文）
        String s = ClickHouseErrorClassifier.sanitize(
                "jdbc:clickhouse://h:8443/default?password=secret&user=admin failed");
        assertTrue(s.contains("jdbc:clickhouse://***"));
        assertTrue(!s.contains("secret"));
        assertTrue(!s.contains("admin"));
        // 非 URL 形态的 user=/password= 也要抹
        assertTrue(ClickHouseErrorClassifier.sanitize("login failed: user=bob password=pw")
                .contains("user=***"));
    }
}
