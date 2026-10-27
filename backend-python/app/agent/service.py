"""Agent 编排：规划 → 并行取数 → 模型分析（可选）→ 兜底规则汇总。

一次问答的完整链路都在这一个函数里，因为 P0 的会话是无状态的：
前端把问题发过来，这里现场取数、现场生成，不存会话、不写库。
"""
from __future__ import annotations

import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from typing import Any

from app.agent import audit, composer, llm, planner
from app.agent.java_tools import call_tool

_TOOL_LABELS = {
    "positions": "查询持仓",
    "watchlist-quotes": "查询自选行情",
    "alert-events": "查询告警事件",
    "market-brief": "查询市场概况",
    "recaps": "查询历史复盘",
    "diary-search": "检索投资日记",
}


def ask(question: str) -> dict[str, Any]:
    started = time.monotonic()
    planned = planner.plan(question)

    results = run_tools(planned)
    rule = composer.compose(results)
    rule_answer = {
        "conclusion": " ".join(rule["conclusion"]),
        "evidence": rule["evidence"],
        "limits": rule["limits"],
    }

    degraded: list[str] = []
    usage: dict[str, Any] | None = None
    model_error: str | None = None

    analysis = llm.analyze(question, results) if llm.is_configured() else None
    if analysis is not None and analysis["ok"]:
        mode = "llm"
        answer = analysis["answer"]
        usage = analysis["usage"]
        # 模型的 limits 可能与工具缺失项不一致，这里强制合并，缺失说明宁多勿少
        answer["limits"] = _dedupe(answer["limits"] + rule_answer["limits"])
    else:
        mode = "rule_based"
        answer = rule_answer
        if analysis is not None:
            model_error = analysis.get("errorCode")
            degraded.append(f"{analysis['error']}；以下内容由规则汇总生成")
        else:
            degraded.append("未配置模型（STOCKCAT_LLM_* 为空），以下内容由规则汇总生成，不是模型分析结果")

    payload = {
        "question": question,
        "mode": mode,
        "answer": answer,
        "toolCalls": [tool_call_view(result) for result in results],
        "dataAsOf": latest_data_as_of(results),
        "generatedAt": _now_iso(),
        "model": usage["model"] if usage else None,
        "usage": usage,
        "degraded": degraded,
        "elapsedMs": int((time.monotonic() - started) * 1000),
    }
    audit.log_run(
        "assistant",
        mode=mode,
        model=payload["model"],
        elapsed_ms=payload["elapsedMs"],
        tool_calls=payload["toolCalls"],
        usage=usage,
        error_code=model_error,
        qChars=len(question),
    )
    return payload


def run_tools(planned: list[tuple[str, dict]]) -> list[dict[str, Any]]:
    """并行调用，保持计划顺序——前端展示的执行过程要和计划一致。

    复盘草稿（``recap_service``）也走这里：取数、执行过程展示两边必须同一套语义。
    """
    if not planned:
        return []
    workers = min(4, len(planned))
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(call_tool, name, params) for name, params in planned]
        return [future.result() for future in futures]


def tool_call_view(result: dict[str, Any]) -> dict[str, Any]:
    body = result.get("data") or {}
    return {
        "tool": result["tool"],
        "label": _TOOL_LABELS.get(result["tool"], result["tool"]),
        "ok": bool(result.get("ok")),
        "summary": _summary(result["tool"], body) if result.get("ok") else None,
        "error": result.get("error"),
        "elapsedMs": result.get("elapsedMs"),
        "dataAsOf": body.get("dataAsOf"),
    }


def _summary(tool: str, body: dict[str, Any]) -> str:
    data = body.get("data") or {}
    if tool == "watchlist-quotes":
        return f"{data.get('count', 0)} 只自选"
    if tool == "positions":
        return f"{data.get('count', 0)} 只持仓"
    if tool == "alert-events":
        return f"{data.get('count', 0)} 条告警"
    if tool == "market-brief":
        return f"{len(data.get('indices') or [])} 个指数"
    if tool == "recaps":
        return f"{data.get('count', 0)} 篇复盘"
    if tool == "diary-search":
        return f"{data.get('count', 0)} 篇日记"
    return "已返回"


def latest_data_as_of(results: list[dict[str, Any]]) -> str | None:
    """取所有工具里最新的观测时点，作为整段回答的"数据截至"。"""
    latest: tuple[datetime, str] | None = None
    for result in results:
        raw = (result.get("data") or {}).get("dataAsOf")
        if not raw:
            continue
        try:
            parsed = datetime.fromisoformat(str(raw).replace("Z", "+00:00"))
        except ValueError:
            continue
        if latest is None or parsed > latest[0]:
            latest = (parsed, str(raw))
    return latest[1] if latest else None


def _dedupe(items: list[str]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for item in items:
        if item and item not in seen:
            seen.add(item)
            out.append(item)
    return out[:20]


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
