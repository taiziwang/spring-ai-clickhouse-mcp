import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** 探测 clickhouse-jdbc 0.10（v2）接受的 URL 参数名。 */
public class JdbcProbe {
    public static void main(String[] args) {
        String[] urls = {
            "jdbc:clickhouse://127.0.0.1:8123/default?socket_timeout=300000",
            "jdbc:clickhouse://127.0.0.1:8123/default?connection_timeout=30000",
            "jdbc:clickhouse://127.0.0.1:8123/default?request_timeout=300000",
            "jdbc:clickhouse://127.0.0.1:8123/default?connection_timeout=30000&socket_timeout=300000",
            "jdbc:clickhouse://127.0.0.1:8123/default?connection_timeout=30000&socket_timeout=300000&retries=0",
            "jdbc:clickhouse://127.0.0.1:8123/default?connection_timeout=30000&socket_timeout=300000&retries=1",
            "jdbc:clickhouse://127.0.0.1:8123/default?connect_timeout=30000&socket_timeout=300000&custom_http_params=readonly%3D1",
        };
        for (String url : urls) {
            System.out.println("\n=== " + url);
            long t0 = System.currentTimeMillis();
            try (Connection c = DriverManager.getConnection(url, "mcp", "mcp123");
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT 1")) {
                rs.next();
                System.out.println("  OK  1=" + rs.getInt(1) + "  (" + (System.currentTimeMillis() - t0) + "ms)");
                // readonly 探针
                try (Statement s2 = c.createStatement()) {
                    s2.execute("SELECT 1 SETTINGS max_threads=1");
                    System.out.println("  readonly: 未强制");
                } catch (Exception e) {
                    String m = e.getMessage() == null ? "" : e.getMessage().replace('\n', ' ');
                    System.out.println("  readonly: 已强制 -> " + (m.length() > 120 ? m.substring(0, 120) : m));
                }
            } catch (Throwable t) {
                Throwable x = t;
                for (int d = 0; x != null && d < 4; d++, x = x.getCause()) {
                    System.out.println("  [" + d + "] " + x.getClass().getSimpleName() + ": "
                            + (x.getMessage() == null ? "" : x.getMessage().replace('\n', ' ')));
                }
            }
        }
    }
}
