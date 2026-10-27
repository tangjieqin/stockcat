"""Java 只读工具客户端（P2.1 白名单的 Python 侧门面）。

设计要点：
- 只有 GET，只有读。清单硬编码在本文件里，想加工具必须改代码 → 天然"只能调用白名单工具"。
- 工具失败**不抛异常**，返回 ok=False + error：Agent 要继续吐出"哪块数据没取到"，
  而不是整段回答失败。唯一例外是 question 本身不合法（由路由层拦）。
- 不做重试：GET 已经失败通常意味着 Java 没起来，重试只会让用户多等两次超时。
"""
from __future__ import annotations

import time
from typing import Any

import httpx

from app.config import settings

JAVA_UNAVAILABLE = "JAVA_UNAVAILABLE"
UPSTREAM_ERROR = "UPSTREAM_ERROR"
UPSTREAM_TIMEOUT = "UPSTREAM_TIMEOUT"

# 工具名 -> (Java 路径, 允许的查询参数)
TOOL_WHITELIST: dict[str, tuple[str, tuple[str, ...]]] = {
    "positions": ("/positions", ()),
    "watchlist-quotes": ("/watchlist-quotes", ()),
    "alert-events": ("/alert-events", ("limit",)),
    "market-brief": ("/market-brief", ("date",)),
    "recaps": ("/recaps", ("from", "to", "limit")),
    "diary-search": ("/diary/search", ("keyword", "limit")),
    "library-search": ("/library/search", ("keyword", "limit")),
}

_client: httpx.Client | None = None


def tool_names() -> list[str]:
    return list(TOOL_WHITELIST)


def get_client() -> httpx.Client:
    global _client
    if _client is None:
        _client = httpx.Client(
            base_url=settings.java_base_url.rstrip("/") + "/api/v1/agent/tools",
            timeout=settings.java_timeout_seconds,
            headers={"User-Agent": "StockCat-Agent/0.1"},
        )
    return _client


def close_client() -> None:
    global _client
    if _client is not None:
        _client.close()
        _client = None


def call_tool(name: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
    """调用一个只读工具。

    返回 ``{"tool", "ok", "data", "error", "elapsedMs"}``；``data`` 是 Java 的统一包装
    ``{tool, dataAsOf, data, missing}``，失败时为 None。
    """
    entry = TOOL_WHITELIST.get(name)
    if entry is None:
        # 模型或规划层给出白名单外的名字：直接拒绝，绝不拼 URL
        return {"tool": name, "ok": False, "data": None, "error": "TOOL_NOT_ALLOWED", "elapsedMs": 0}

    path, allowed = entry
    query = {k: v for k, v in (params or {}).items() if k in allowed and v is not None}
    started = time.monotonic()
    try:
        resp = get_client().get(path, params=query)
    except httpx.TimeoutException:
        return _fail(name, UPSTREAM_TIMEOUT, "Java 工具调用超时", started)
    except httpx.HTTPError as exc:
        return _fail(name, JAVA_UNAVAILABLE,
                     f"Java 服务不可用（{settings.java_base_url}），请先启动 backend-java：{exc}", started)

    elapsed = _elapsed_ms(started)
    if resp.status_code != 200:
        code, message = _error_of(resp)
        return {"tool": name, "ok": False, "data": None,
                "error": f"{code}: {message}", "elapsedMs": elapsed}
    try:
        return {"tool": name, "ok": True, "data": resp.json(), "error": None, "elapsedMs": elapsed}
    except ValueError:
        return {"tool": name, "ok": False, "data": None,
                "error": "INVALID_RESPONSE: Java 工具返回的不是 JSON", "elapsedMs": elapsed}


def _fail(name: str, code: str, detail: str, started: float) -> dict[str, Any]:
    return {"tool": name, "ok": False, "data": None,
            "error": f"{code}: {detail}", "elapsedMs": _elapsed_ms(started)}


def _elapsed_ms(started: float) -> int:
    return int((time.monotonic() - started) * 1000)


def _error_of(resp: httpx.Response) -> tuple[str, str]:
    try:
        body = resp.json()
        return str(body.get("code") or "UPSTREAM_ERROR"), str(body.get("message") or f"HTTP {resp.status_code}")
    except ValueError:
        return UPSTREAM_ERROR, f"HTTP {resp.status_code}"
