package com.example.chmcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 入口。
 *
 * <p>本项目是 ClickHouse/mcp-clickhouse（Python/FastMCP）的 Spring AI 等价改写，
 * 架构沿用 spring-ai-hive-mcp 的形态：<b>单个可执行 fat jar</b>，Servlet 容器由
 * Spring Boot 内嵌 Tomcat 提供。启动方式：
 *
 * <pre>
 *   bin/run.sh                                       # 前台 HTTP 模式
 *   bin/start-http.sh                                # 后台 HTTP 模式
 *   bin/run-stdio.sh                                 # stdio 模式（由 MCP 客户端拉起）
 *   java -jar target/spring-ai-clickhouse-mcp.jar    # 等价
 * </pre>
 *
 * <p>与 Python 版的对应关系：
 * <ul>
 *   <li>{@code run_query} / {@code list_databases} / {@code list_tables} —— 同名工具</li>
 *   <li>{@code CLICKHOUSE_ALLOW_WRITE_ACCESS} / {@code CLICKHOUSE_ALLOW_DROP} ——
 *       对应 {@code clickhouse-mcp.allow-write-access} / {@code allow-drop}</li>
 *   <li>{@code /health} —— HTTP 模式下的健康检查端点，有意不做认证（K8s 探针）</li>
 *   <li>chDB（嵌入式引擎）是 Python 生态的能力，Java 侧无对应物，不移植</li>
 * </ul>
 */
@SpringBootApplication
public class ClickHouseMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClickHouseMcpApplication.class, args);
    }
}
