-- 端到端验证用的测试数据（ClickHouse 25.8 实测）
-- 用法：docker exec -i ch-mcp-test clickhouse-client --user mcp --password mcp123 --multiquery < tools/seed_clickhouse.sql

CREATE DATABASE IF NOT EXISTS demo;

-- 200 行，覆盖 Date / LowCardinality / UInt64 / Decimal / Array 五种类型
CREATE TABLE demo.events (
  event_date Date,
  event_type LowCardinality(String),
  user_id UInt64,
  amount Decimal(18,4),
  tags Array(String)
) ENGINE = MergeTree ORDER BY (event_date, event_type) COMMENT '测试事件表';

INSERT INTO demo.events
SELECT today() - (number % 5),
       ['click','view','purchase'][1 + number % 3],
       number,
       toDecimal64(number * 1.5, 4),
       ['a','b']
FROM numbers(200);

CREATE TABLE demo.users (id UInt64, name String, created_at DateTime) ENGINE = MergeTree ORDER BY id;
INSERT INTO demo.users VALUES (1,'alice',now()), (2,'bob',now());

-- 验证「超 JS 安全范围的整数 / 高精度 DECIMAL 以字符串返回」
CREATE TABLE demo.big_numbers (v UInt64, d Decimal(38,10)) ENGINE = MergeTree ORDER BY v;
INSERT INTO demo.big_numbers VALUES (18446744073709551615, 12345678901234567890.0123456789);

SELECT 'events' AS t, count() FROM demo.events
UNION ALL SELECT 'users', count() FROM demo.users
UNION ALL SELECT 'big', count() FROM demo.big_numbers;
