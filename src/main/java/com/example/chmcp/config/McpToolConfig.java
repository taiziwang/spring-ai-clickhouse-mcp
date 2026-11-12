package com.example.chmcp.config;

import com.example.chmcp.tool.ClickHouseTools;
import com.example.chmcp.tool.DiagnosticsTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@link ClickHouseTools} 上 {@code @Tool} 标注的方法注册成 MCP 工具。
 *
 * <p>Spring AI 的 MCP Server 自动配置会扫描容器里的 {@link ToolCallbackProvider} bean
 * 并注册其中的工具，不需要手工拼 {@code ToolSpecification}。
 */
@Configuration
public class McpToolConfig {

    /**
     * 业务工具必注册；运维诊断工具（{@link DiagnosticsTools}）按开关决定在不在。
     *
     * <p>用 {@link ObjectProvider} 而不是直接注入 —— {@code DiagnosticsTools} 上有
     * {@code @ConditionalOnProperty}，关掉时容器里根本没有这个 bean，
     * 直接注入会启动失败。
     */
    @Bean
    public ToolCallbackProvider clickHouseToolCallbackProvider(
            ClickHouseTools clickHouseTools,
            ObjectProvider<DiagnosticsTools> diagnosticsTools) {

        List<Object> toolObjects = new ArrayList<>();
        toolObjects.add(clickHouseTools);
        DiagnosticsTools diagnostics = diagnosticsTools.getIfAvailable();
        if (diagnostics != null) {
            toolObjects.add(diagnostics);
        }
        return MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects.toArray())
                .build();
    }
}
