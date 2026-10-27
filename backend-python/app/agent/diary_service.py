"""日记辅助的编排：整理单篇、按关键词回顾。两个场景都**不落库**。

整理（refine）：Java 把标题、正文和「已知标的」白名单送进来，这里产出建议稿。
写不写回正文由用户在编辑器里点保存决定——AI 不碰用户已经写下的东西。

回顾（review）：先走只读的 ``diary-search`` 工具检索，再让模型归纳。
检索不到就到此为止：让模型"就着关键词聊聊"是最容易编造的一段。
"""
from __future__ import annotations

import time
from datetime import datetime, timezone
from typing import Any

from app.agent import audit, diary_assist, llm, service
from app.errors import VALIDATION_ERROR, ApiError

_DEFAULT_REVIEW_HITS = 5
_MAX_REVIEW_HITS = 10
_MIN_BODY_CHARS = 8


def refine(title: str, content: str, known_instruments: list[dict[str, Any]]) -> dict[str, Any]:
    """整理一篇日记，返回建议稿。标题与正文由 Java 从库里读或由前端传入（未保存也能整理）。"""
    started = time.monotonic()
    body = (content or "").strip()
    if len(body) < _MIN_BODY_CHARS:
        raise ApiError(
            400, VALIDATION_ERROR,
            f"正文太短，没有可整理的内容（至少 {_MIN_BODY_CHARS} 个字）",
        )

    result = diary_assist.extract(title, body, known_instruments)
    limitations = [diary_assist.BODY_LIMITATION]
    if not known_instruments:
        limitations.append("自选与持仓均为空，无法识别文中涉及的标的。")

    mode = "rule_based"
    usage: dict[str, Any] | None = None
    degraded: list[str] = []
    model_error: str | None = None

    if llm.is_configured():
        view = llm.refine_diary(diary_assist.refine_prompt(title, body, known_instruments))
        if view["ok"]:
            accepted = diary_assist.accept_refine(view["view"], known_instruments)
            if accepted is not None:
                mode = "llm"
                usage = view["usage"]
                dropped = accepted.pop("droppedInstruments", [])
                result = accepted
                if len(body) > diary_assist.MAX_TEXT_CHARS:
                    # 只有模型路径会截断；截断必须说出来，否则用户以为 AI 读完了全文
                    limitations.append(
                        f"正文 {len(body)} 字，超出模型单次上限，只整理了前 "
                        f"{diary_assist.MAX_TEXT_CHARS} 字，后半段请自行检查。"
                    )
                if dropped:
                    # 丢弃要让人看见：否则用户会以为"AI 没认出来"，而不是"AI 认错了被我拦下"
                    limitations.append(
                        "模型给出的这些标的不在自选/持仓中，已丢弃：" + "、".join(dropped)
                    )
            else:
                model_error = llm.MODEL_OUTPUT_INVALID
                degraded.append("模型输出缺少必要段落，以下内容由规则提取生成")
        else:
            model_error = view.get("errorCode")
            degraded.append(f"{view['error']}；以下内容由规则提取生成")
    else:
        degraded.append("未配置模型（STOCKCAT_LLM_* 为空），以下内容由规则提取生成，不是模型整理")

    payload = {
        "title": title or None,
        "mode": mode,
        "result": result,
        "knownInstrumentCount": len(known_instruments or []),
        "limitations": limitations,
        "generatedAt": _now_iso(),
        "model": usage["model"] if usage else None,
        "usage": usage,
        "degraded": degraded,
        "elapsedMs": int((time.monotonic() - started) * 1000),
    }
    audit.log_run(
        "diary-refine",
        mode=mode,
        model=payload["model"],
        elapsed_ms=payload["elapsedMs"],
        usage=usage,
        error_code=model_error,
        chars=len(body),
        instruments=len(result.get("instruments") or []),
    )
    return payload


def review(keyword: str, limit: int | None = None) -> dict[str, Any]:
    """按关键词回顾历史日记。命中为空时明确说"没有找到"，不调模型。"""
    started = time.monotonic()
    query = (keyword or "").strip()
    if not query:
        raise ApiError(400, VALIDATION_ERROR, "keyword 必填：日记回顾需要一个检索词")
    effective = min(limit if limit and limit > 0 else _DEFAULT_REVIEW_HITS, _MAX_REVIEW_HITS)

    results = service.run_tools([("diary-search", {"keyword": query, "limit": effective})])
    tool = results[0]
    hits = _hits_of(tool)

    limitations = [diary_assist.REVIEW_LIMITATION]
    if not tool.get("ok"):
        limitations.append("日记检索失败：" + str(tool.get("error")))

    base = {
        "keyword": query,
        "hitCount": len(hits),
        "empty": not hits,
        "hits": hits,
        "toolCalls": [service.tool_call_view(tool)],
        "limitations": limitations,
        "generatedAt": _now_iso(),
    }

    if not hits:
        payload = {
            **base,
            "mode": "rule_based",
            "theme": None,
            "threads": [],
            "recurring": [],
            "gaps": [],
            "citations": [],
            "model": None,
            "usage": None,
            "degraded": [],
            "elapsedMs": int((time.monotonic() - started) * 1000),
        }
        audit.log_run(
            "diary-review",
            mode="empty",
            elapsed_ms=payload["elapsedMs"],
            tool_calls=payload["toolCalls"],
            error_code=None if tool.get("ok") else "TOOL_FAILED",
            keyword=query,
            hits=0,
        )
        return payload

    mode = "rule_based"
    view = {"theme": None, "threads": [], "recurring": [], "gaps": [],
            "citations": _fallback_citations(hits)}
    usage: dict[str, Any] | None = None
    degraded: list[str] = []
    model_error: str | None = None

    if llm.is_configured():
        answer = llm.review_diary(diary_assist.review_prompt(query, hits))
        if answer["ok"]:
            accepted = diary_assist.accept_review(answer["view"], hits)
            if accepted is not None:
                mode = "llm"
                usage = answer["usage"]
                view = accepted
            else:
                model_error = llm.MODEL_OUTPUT_INVALID
                degraded.append("模型输出缺少必要段落，以下只做检索罗列")
        else:
            model_error = answer.get("errorCode")
            degraded.append(f"{answer['error']}；以下只做检索罗列")
    else:
        degraded.append("未配置模型（STOCKCAT_LLM_* 为空），以下只做检索罗列，未做归纳")

    payload = {
        **base,
        "mode": mode,
        "theme": view["theme"],
        "threads": view["threads"],
        "recurring": view["recurring"],
        "gaps": view["gaps"],
        "citations": view["citations"],
        "model": usage["model"] if usage else None,
        "usage": usage,
        "degraded": degraded,
        "elapsedMs": int((time.monotonic() - started) * 1000),
    }
    audit.log_run(
        "diary-review",
        mode=mode,
        model=payload["model"],
        elapsed_ms=payload["elapsedMs"],
        tool_calls=payload["toolCalls"],
        usage=usage,
        error_code=model_error,
        keyword=query,
        hits=len(hits),
    )
    return payload


def _hits_of(tool_result: dict[str, Any]) -> list[dict[str, Any]]:
    """把工具返回的 DiaryHit 摊平成回顾需要的形状；缺字段照样保留条目。"""
    if not tool_result.get("ok"):
        return []
    body = (tool_result.get("data") or {}).get("data") or {}
    hits: list[dict[str, Any]] = []
    for item in body.get("hits") or []:
        hits.append({
            "nodeId": item.get("nodeId"),
            "title": item.get("title"),
            "path": item.get("path"),
            "preview": item.get("contentPreview"),
            "updatedAt": item.get("updatedAt"),
        })
    return hits


def _fallback_citations(hits: list[dict[str, Any]]) -> list[str]:
    """规则模式下的引用：直接用命中列表拼，绝不出现列表外的篇目。"""
    out: list[str] = []
    for item in hits[:5]:
        date = str(item.get("updatedAt") or "")[:10]
        out.append(f"《{item.get('title')}》{date}".strip())
    return out


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
