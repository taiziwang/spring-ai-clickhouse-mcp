#!/usr/bin/env python3
"""
端到端验证：对真实的 ClickHouse 跑一遍全部 MCP 工具，覆盖成功路径与各类失败路径。

前置：ClickHouse 已启动且灌好测试数据（见 tools/seed_clickhouse.sql），
      服务以默认只读配置启动（allow-write-access=false, on-truncate=ERROR）。

用法：
    python3 tools/e2e_clickhouse_test.py [http://127.0.0.1:8000]

退出码 0 表示全部用例通过。
"""
import json
import sys
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8000").rstrip("/")
URL = BASE + "/mcp"
PROTOCOL = "2025-06-18"

session_id = None
_next_id = 0
passed = 0
failed = []


def check(name, cond, detail=""):
    global passed
    if cond:
        passed += 1
        print("  PASS  %s" % name)
    else:
        failed.append(name)
        print("  FAIL  %s  %s" % (name, detail))


def _post(payload):
    global session_id
    req = urllib.request.Request(URL, data=json.dumps(payload).encode("utf-8"), method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json, text/event-stream")
    req.add_header("MCP-Protocol-Version", PROTOCOL)
    if session_id:
        req.add_header("Mcp-Session-Id", session_id)
    with urllib.request.urlopen(req, timeout=60) as resp:
        sid = resp.headers.get("Mcp-Session-Id") or resp.headers.get("mcp-session-id")
        if sid:
            session_id = sid
        return resp.read().decode("utf-8", "replace")


def parse_body(raw):
    text = raw.strip()
    if not text:
        return None
    if text.startswith("{") or text.startswith("["):
        return json.loads(text)
    datas = [l[5:].strip() for l in text.splitlines() if l.startswith("data:")]
    return json.loads(datas[-1]) if datas else None


def rpc(method, params=None, notify=False):
    global _next_id
    payload = {"jsonrpc": "2.0", "method": method}
    if params is not None:
        payload["params"] = params
    if not notify:
        _next_id += 1
        payload["id"] = _next_id
    raw = _post(payload)
    if notify:
        return None
    msg = parse_body(raw)
    if msg is None:
        raise RuntimeError("空响应: " + method)
    if "error" in msg:
        raise RuntimeError("JSON-RPC 错误: %s" % msg["error"])
    return msg.get("result")


def call(tool, args=None):
    """调工具，返回 (payload_dict, is_error)。payload 是 JSON 解析后的结果。"""
    res = rpc("tools/call", {"name": tool, "arguments": args or {}})
    is_error = bool(res.get("isError"))
    text = res["content"][0]["text"] if res.get("content") else ""
    try:
        return json.loads(text), is_error
    except json.JSONDecodeError:
        return {"_raw": text}, is_error


def expect_ok(name, tool, args=None, predicate=None):
    payload, err = call(tool, args)
    ok = not err and (predicate(payload) if predicate else True)
    check(name, ok, "-> %s" % json.dumps(payload, ensure_ascii=False)[:300])
    return payload


def expect_err(name, tool, args, code):
    payload, err = call(tool, args)
    ok = err and payload.get("code") == code
    check(name, ok, "-> %s" % json.dumps(payload, ensure_ascii=False)[:300])
    return payload


def main():
    print("== initialize ==")
    init = rpc("initialize", {
        "protocolVersion": PROTOCOL,
        "capabilities": {},
        "clientInfo": {"name": "e2e", "version": "1.0"},
    })
    print("  server:", init["serverInfo"]["name"], init["serverInfo"]["version"])
    rpc("notifications/initialized", notify=True)

    print("\n== /health ==")
    with urllib.request.urlopen(BASE + "/health", timeout=20) as r:
        body = r.read().decode()
        check("health 返回 200（ClickHouse 可达）", r.status == 200, body)

    print("\n== tools/list：参数名契约 ==")
    tools = rpc("tools/list", {})["tools"]
    names = {t["name"] for t in tools}
    check("5 个工具齐全", names == {"list_databases", "list_tables", "run_query",
                                  "get_pool_status", "get_server_status"}, str(names))
    props = set(tools[[t["name"] for t in tools].index("list_tables")]["inputSchema"]["properties"])
    check("list_tables 参数名为 snake_case（与 Python 版一致）",
          {"database", "like", "not_like", "include_detailed_columns",
           "page_size", "page_token"} <= props, str(props))

    print("\n== list_databases ==")
    dbs = expect_ok("返回数据库清单", "list_databases",
                    predicate=lambda p: "demo" in p["databases"] and "system" in p["databases"])
    check("truncated=false", dbs.get("truncated") is False)

    print("\n== list_tables ==")
    all_tables = expect_ok("demo 库三张表", "list_tables", {"database": "demo"},
                           lambda p: {t["name"] for t in p["tables"]} ==
                                     {"events", "users", "big_numbers"})
    check("无下一页时 nextPageToken=null", all_tables.get("nextPageToken") is None)
    ev = [t for t in all_tables["tables"] if t["name"] == "events"][0]
    check("引擎为 MergeTree", ev["engine"] == "MergeTree", ev["engine"])
    check("表注释带出", ev["comment"] == "测试事件表", ev["comment"])
    check("建表语句带出", "CREATE TABLE" in ev["createTableQuery"])

    like = expect_ok("like 过滤（ev%）", "list_tables", {"database": "demo", "like": "ev%"},
                     lambda p: [t["name"] for t in p["tables"]] == ["events"])
    not_like = expect_ok("not_like 过滤（%s）", "list_tables",
                         {"database": "demo", "not_like": "%s"},
                         lambda p: "events" not in [t["name"] for t in p["tables"]])
    detailed = expect_ok("include_detailed_columns 带列详情", "list_tables",
                         {"database": "demo", "like": "events",
                          "include_detailed_columns": True},
                         lambda p: len(p["tables"][0]["columns"]) == 5)
    cols = {c["name"]: c["type"] for c in detailed["tables"][0]["columns"]}
    check("列类型正确（amount Decimal(18,4)）",
          cols.get("amount") == "Decimal(18, 4)", str(cols))
    check("列注释字段存在", "comment" in detailed["tables"][0]["columns"][0])

    page1 = expect_ok("page_size=1 首页", "list_tables", {"database": "demo", "page_size": 1},
                      lambda p: len(p["tables"]) == 1 and p["nextPageToken"] == "1")
    page2 = expect_ok("page_token 翻页", "list_tables",
                      {"database": "demo", "page_size": 1,
                       "page_token": page1["nextPageToken"]},
                      lambda p: p["tables"][0]["name"] != page1["tables"][0]["name"])

    print("\n== run_query：成功路径 ==")
    r = expect_ok("SELECT count()", "run_query",
                  {"sql": "SELECT count() AS c FROM demo.events"},
                  lambda p: p["rows"][0][0] == 200 and p["rowCount"] == 1)
    check("count() 返回数值而非字符串（UInt64 在安全范围内）",
          isinstance(r["rows"][0][0], int), repr(r["rows"][0][0]))
    check("elapsedMs 有值", r["elapsedMs"] >= 0)

    agg = expect_ok("GROUP BY 聚合", "run_query",
                    {"sql": "SELECT event_type, count() AS c, sum(amount) AS s "
                            "FROM demo.events GROUP BY event_type ORDER BY event_type"},
                    lambda p: p["rowCount"] == 3 and len(p["columns"]) == 3)
    check("DECIMAL 以字符串返回（不丢精度）",
          isinstance(agg["rows"][0][2], str), str(agg["rows"][0]))

    big = expect_ok("UInt64 超 JS 安全范围转字符串", "run_query",
                    {"sql": "SELECT v, d FROM demo.big_numbers"},
                    lambda p: p["rows"][0][0] == "18446744073709551615")
    check("Decimal(38,10) 保精度", big["rows"][0][1].startswith("12345678901234567890.0123"),
          big["rows"][0][1])

    expect_ok("DESCRIBE 表结构", "run_query", {"sql": "DESCRIBE demo.events"},
              lambda p: p["rowCount"] == 5)
    expect_ok("WITH 子句", "run_query",
              {"sql": "WITH 10 AS n SELECT n * 2 AS x"},
              lambda p: p["rows"][0][0] == 20 and isinstance(p["rows"][0][0], int))
    expect_ok("EXPLAIN 执行计划", "run_query",
              {"sql": "EXPLAIN SELECT * FROM demo.events"}, lambda p: p["rowCount"] > 0)
    # SHOW CREATE TABLE 是只读元数据语句，语句体里的 CREATE 不能被写关键字误杀
    expect_ok("SHOW CREATE TABLE", "run_query", {"sql": "SHOW CREATE TABLE demo.events"},
              lambda p: p["rowCount"] == 1)
    expect_ok("中文与引号转义", "run_query",
              {"sql": "SELECT '中文' AS s, 'it''s' AS q"},
              lambda p: p["rows"][0][0] == "中文")

    print("\n== 服务端只读强制（readonly=1）==")
    # SELECT ... SETTINGS 是合法的只读语句，但 readonly=1 下不允许改任何设置 ——
    # 用它可以干净地区分「服务端真的只读」和「只是客户端守卫拦住了」。
    ro = expect_err("SETTINGS 被服务端只读拒绝", "run_query",
                    {"sql": "SELECT 1 SETTINGS max_threads=1"}, "AccessDenied")
    check("hint 指向写开关", "allow-write-access" in ro.get("hint", ""), ro.get("hint", ""))

    print("\n== run_query：失败路径分类 ==")
    expect_err("INSERT 被只读拦截", "run_query",
               {"sql": "INSERT INTO demo.users VALUES (3,'c',now())"}, "AccessDenied")
    expect_err("DROP 被拦截", "run_query", {"sql": "DROP TABLE demo.users"}, "AccessDenied")
    expect_err("语法错误 → InvalidArgument", "run_query",
               {"sql": "SELEC 1"}, "InvalidArgument")
    expect_err("表不存在 → InvalidArgument", "run_query",
               {"sql": "SELECT * FROM demo.no_such_table"}, "InvalidArgument")
    expect_err("列不存在 → InvalidArgument", "run_query",
               {"sql": "SELECT no_such_col FROM demo.events"}, "InvalidArgument")
    expect_err("多语句被拦截", "run_query",
               {"sql": "SELECT 1; SELECT 2"}, "InvalidArgument")
    expect_err("非法库名（注入尝试）", "list_tables",
               {"database": "demo`; DROP TABLE x"}, "InvalidArgument")
    expect_err("非法 page_token", "list_tables",
               {"database": "demo", "page_token": "abc"}, "InvalidArgument")

    print("\n== 结果预算 ==")
    expect_err("超过 max-rows 触发 QueryLimitExceeded", "run_query",
               {"sql": "SELECT * FROM numbers(10000)"}, "QueryLimitExceeded")

    print("\n== 运维诊断工具 ==")
    pool = expect_ok("get_pool_status", "get_pool_status", {"probe": True},
                     lambda p: p["probeOk"] is True and p["initialized"] is True)
    check("池状态 OK", pool["status"] == "OK", pool["status"])
    check("连接串抹掉凭据", "password" not in json.dumps(pool["config"]).lower(),
          json.dumps(pool["config"], ensure_ascii=False))

    st = expect_ok("get_server_status", "get_server_status",
                   predicate=lambda p: p["limits"]["accessMode"] == "READONLY")
    check("执行统计已累计", st["queryStats"]["total"] > 0, str(st["queryStats"]))
    check("失败按 code 聚合", "InvalidArgument" in st["queryStats"]["failuresByCode"],
          str(st["queryStats"]["failuresByCode"]))
    check("传输模式为 http", st["transport"] == "http")
    check("诊断结论非空", len(st["diagnosis"]) > 0)

    print("\n================ 结果 ================")
    print("通过 %d 项，失败 %d 项" % (passed, len(failed)))
    if failed:
        print("失败用例：")
        for f in failed:
            print("  - " + f)
        sys.exit(1)
    print("✅ 端到端验证全部通过")


if __name__ == "__main__":
    main()
