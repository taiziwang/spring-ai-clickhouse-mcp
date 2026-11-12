package com.example.chmcp.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * {@code GET /health} —— 等价 Python 版 mcp-clickhouse 的 /health 端点。
 *
 * <p><b>有意不做认证</b>：给 K8s 存活/就绪探针用，语义与 Python 版一致 ——
 * ClickHouse 可达返回 {@code 200 OK}，不可达返回 {@code 503}。
 *
 * <p>探测结果缓存 {@value #CACHE_MS} 毫秒：探针通常会打得很勤（K8s 默认 10s 一次，
 * 有人还会配到 1s），每次都真去连 ClickHouse 会白白占用池连接。
 *
 * <p>只在 Web（HTTP）模式下注册；stdio 模式没有 Servlet 容器，不加载本类。
 */
@RestController
@ConditionalOnWebApplication
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private static final long CACHE_MS = 5_000;

    private final DataSource dataSource;

    /** 上一次探测结果与时刻。仅健康检查用，容忍轻微竞争（最坏多探测一次）。 */
    private volatile long lastCheckedAt = 0;
    private volatile boolean lastOk = false;
    private volatile String lastError = "";

    public HealthController(DataSource clickHouseDataSource) {
        this.dataSource = clickHouseDataSource;
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        long now = System.currentTimeMillis();
        if (now - lastCheckedAt >= CACHE_MS) {
            probe();
            lastCheckedAt = now;
        }
        if (lastOk) {
            return ResponseEntity.ok("OK");
        }
        return ResponseEntity.status(503).body("ClickHouse unreachable: " + lastError);
    }

    private void probe() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(5);
            stmt.execute("SELECT 1");
            lastOk = true;
            lastError = "";
        } catch (Exception e) {
            lastOk = false;
            lastError = e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage().length() > 200 ? e.getMessage().substring(0, 200)
                    : e.getMessage();
            log.warn("/health 探测 ClickHouse 失败: {}", lastError);
        }
    }
}
