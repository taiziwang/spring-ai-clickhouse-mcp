#!/usr/bin/env python3
"""
MCP Streamable HTTP 冒烟测试 —— 验证 spring-ai-clickhouse-mcp 的协议面与工具清单。

不依赖 ClickHouse 在线：本脚本只做
  1. initialize 握手（拿到 Mcp-Session-Id）
  2. tools/list（校验 5 个工具都在，且入参名是真实参数名而不是 arg0/arg1）
  3. 调一次 list_databases，期望拿到 isError=true 的 DatabaseUnavailable 分类 JSON
     —— 这条同时验证了「错误分类 + 框架自动置 isError」的整条链路

用法：
    python3 tools/mcp_smoke_test.py [http://127.0.0.1:8000]

退出码 0 表示全部通过。
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


def _post(payload):
    global session_id
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(URL, data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json, text/event-stream")
    req.add_header("MCP-Protocol-Version", PROTOCOL)
    if session_id:
        req.add_header("Mcp-Session-Id", session_id)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            sid = resp.headers.get("Mcp-Session-Id") or resp.headers.get("mcp-session-id")
            if sid:
                session_id = sid
            raw = resp.read().decode("utf-8", "replace")
            return raw
    except urllib.error.HTTPError as e:
        print("HTTP %s: %s" % (e.code, e.read().decode("utf-8", "replace")[:500]))
        raise


def parse_body(raw):
    """兼容两种响应形态：纯 JSON，或 SSE（event: message / data: {...}）。"""
    text = raw.strip()
    if not text:
        return None
    if text.startswith("{") or text.startswith("["):
        return json.loads(text)
    datas = []
    for line in text.splitlines():
        if line.startswith("data:"):
            datas.append(line[5:].strip())
    if not datas:
        return None
    return json.loads(datas[-1])


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


def main():
    print("== initialize ==")
    init = rpc("initialize", {
        "protocolVersion": PROTOCOL,
        "capabilities": {},
        "clientInfo": {"name": "smoke-test", "version": "1.0"},
    })
    print("  server  :", init["serverInfo"])
    print("  protocol:", init.get("protocolVersion"))
    print("  session :", session_id)
    assert session_id, "服务端没返回 Mcp-Session-Id"

    rpc("notifications/initialized", notify=True)

    print("== tools/list ==")
    result = rpc("tools/list", {})
    tools = result["tools"]
    names = sorted(t["name"] for t in tools)
    print("  工具:", names)
    expected = {"list_databases", "list_tables", "run_query",
                "get_pool_status", "get_server_status"}
    missing = expected - set(names)
    assert not missing, "缺少工具: %s" % missing

    # 入参名必须是真实参数名 —— 若 -parameters 没生效会退化成 arg0/arg1
    by_name = {t["name"]: t for t in tools}
    lt = by_name["list_tables"]["inputSchema"]
    props = sorted(lt.get("properties", {}).keys())
    print("  list_tables 入参:", props)
    for required in ("database", "like", "not_like", "include_detailed_columns",
                     "page_size", "page_token"):
        assert required in props, "list_tables 缺少入参 %s（-parameters 可能没生效）" % required
    rq = by_name["run_query"]["inputSchema"]
    assert "sql" in rq.get("properties", {}), "run_query 缺少 sql 入参"

    print("== tools/call list_databases（期望 DatabaseUnavailable）==")
    call = rpc("tools/call", {"name": "list_databases", "arguments": {}})
    text = call["content"][0]["text"] if call.get("content") else ""
    print("  isError:", call.get("isError"))
    print("  content:", text[:300])
    assert call.get("isError") is True, "ClickHouse 不可达时应当返回 isError=true"
    payload = json.loads(text)
    assert payload["code"] == "DatabaseUnavailable", \
        "期望 DatabaseUnavailable，实际 %s" % payload["code"]
    assert payload["retryable"] is True
    assert payload["hint"], "hint 不应为空"

    print("\n✅ 冒烟测试全部通过")


if __name__ == "__main__":
    main()
