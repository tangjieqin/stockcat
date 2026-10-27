"""把工具返回的数据拼成"人话"。

这是**未配置模型时的回答来源**（mode=rule_based）。它不做分析、不下判断，
只把数据里的关键事实讲清楚——宁可朴素，也不能编。

已配置模型时，这部分仍会作为 fallback：模型调用失败或输出不可解析时，
用户看到的还是真实数据，而不是一片错误。
"""
from __future__ import annotations

from typing import Any

# 格式化统一走 formatting，问答与复盘草稿对同一个数字必须写出同一个样子
from app.agent.formatting import cn_time as _cn_time
from app.agent.formatting import money as _money
from app.agent.formatting import pct as _pct
from app.agent.formatting import price as _price
from app.agent.formatting import qty as _qty
from app.agent.formatting import signed as _signed


# 两个"空"的措辞在这里定义：service 需要判断"这段结论是不是兜底文案"，
# 拿字符串去比对是最笨但最不容易漂的办法（文案只有一处）。
EMPTY_RESULT_TEXT = "已查询相关数据，但没有找到符合条件的内容。"
ALL_FAILED_TEXT = "这次没有取到可用的业务数据，无法给出结论。"


def compose(results: list[dict[str, Any]]) -> dict[str, list[str]]:
    """返回 {conclusion, evidence, limits}，三个都是字符串列表。"""
    conclusion: list[str] = []
    evidence: list[str] = []
    limits: list[str] = []

    for result in results:
        if not result.get("ok"):
            limits.append(f"{result['tool']} 调用失败：{result.get('error')}")
            continue
        body = result.get("data") or {}
        data = body.get("data") or {}
        handler = _HANDLERS.get(result["tool"])
        if handler is not None:
            text, facts = handler(data)
            if text:
                conclusion.append(text)
            evidence.extend(facts)
        for item in body.get("missing") or []:
            limits.append(f"{result['tool']}：{item}")

    if not conclusion:
        # 区分两种"空"：一个都没取到 vs 取到了但没有符合条件的内容（如日记无命中）
        all_failed = all(not r.get("ok") for r in results) if results else True
        conclusion.append(ALL_FAILED_TEXT if all_failed else EMPTY_RESULT_TEXT)
    return {"conclusion": conclusion, "evidence": evidence[:20], "limits": _dedupe(limits)[:20]}


# ----------------------------------------------------------------- 各工具的陈述


def _positions(data: dict[str, Any]) -> tuple[str | None, list[str]]:
    items = data.get("items") or []
    facts: list[str] = []
    if not items:
        return "当前没有持仓记录（交易流水里没有未清仓的标的）。", facts

    parts = [f"当前持有 {len(items)} 只标的"]
    market_value = _money(data.get("totalMarketValue"))
    if market_value:
        parts.append(f"合计市值 {market_value} 元")
    unrealized = _signed(data.get("totalUnrealizedPnl"))
    if unrealized:
        parts.append(f"浮动盈亏 {unrealized} 元")
    realized = _signed(data.get("totalRealizedPnl"))
    if realized:
        parts.append(f"累计已实现 {realized} 元")
    if data.get("priceComplete") is False:
        parts.append("其中有持仓缺有效行情，合计值只含能取到价格的持仓")
    text = "，".join(parts) + "。"

    for item in items[:5]:
        facts.append(
            f"{_name(item)}：{_qty(item.get('quantity'))} 股，成本 {_price(item.get('avgCost')) or '—'}，"
            f"最新 {_price(item.get('lastPrice')) or '—'}，市值 {_money(item.get('marketValue')) or '—'}，"
            f"浮盈 {_signed(item.get('unrealizedPnl')) or '—'}"
            f"（{_cn_time(item.get('priceAsOf'))} {item.get('priceStatus')}）"
        )
    return text, facts


def _watchlist(data: dict[str, Any]) -> tuple[str | None, list[str]]:
    items = data.get("items") or []
    facts: list[str] = []
    if not items:
        return "自选列表为空。", facts

    quoted = [i for i in items if i.get("changePercent") is not None]
    up = sum(1 for i in quoted if i["changePercent"] > 0)
    down = sum(1 for i in quoted if i["changePercent"] < 0)
    flat = len(quoted) - up - down
    text = f"自选 {len(items)} 只，其中有有效行情的 {len(quoted)} 只：涨 {up}、跌 {down}、平 {flat}。"
    if quoted:
        best = max(quoted, key=lambda i: i["changePercent"])
        worst = min(quoted, key=lambda i: i["changePercent"])
        text += (f"领涨 {_name(best)} {_pct(best['changePercent'])}，"
                 f"领跌 {_name(worst)} {_pct(worst['changePercent'])}。")
    for item in items[:8]:
        facts.append(
            f"{_name(item)}：{_price(item.get('lastPrice')) or '—'}，"
            f"{_pct(item.get('changePercent')) or '—'}，"
            f"{item.get('groupName') or '自选'}（{item.get('dataStatus')}，"
            f"{_cn_time(item.get('collectedAt'))}）"
        )
    return text, facts


def _alerts(data: dict[str, Any]) -> tuple[str | None, list[str]]:
    items = data.get("items") or []
    facts: list[str] = []
    if not items:
        return "最近没有触发告警事件。", facts

    latest = items[0]
    text = (f"最近触发 {len(items)} 条告警，最新一条：{_cn_time(latest.get('triggeredAt'))} "
            f"{latest.get('symbol')} 的「{latest.get('ruleName')}」。")
    for item in items:
        facts.append(
            f"{_cn_time(item.get('triggeredAt'))} {item.get('symbol')} 「{item.get('ruleName')}」"
            f"（{item.get('ruleType')}，触发值 {_money(item.get('observedPrice')) or '—'}，"
            f"涨幅 {_pct(item.get('observedChangePercent')) or '—'}）"
        )
    return text, facts


def _market_brief(data: dict[str, Any]) -> tuple[str | None, list[str]]:
    facts: list[str] = []
    parts: list[str] = []
    for index in data.get("indices") or []:
        parts.append(f"{index.get('name')} {_money(index.get('lastPrice')) or '—'}"
                     f"（{_pct(index.get('changePercent')) or '—'}）")
        facts.append(f"{index.get('name')}：{_money(index.get('lastPrice')) or '—'}，"
                     f"{_pct(index.get('changePercent')) or '—'}，行情时间 {_cn_time(index.get('marketTime'))}")
    breadth = data.get("watchlistBreadth") or {}
    breadth_text = ""
    if breadth.get("total"):
        breadth_text = (f"自选涨 {breadth.get('up')}、跌 {breadth.get('down')}、平 {breadth.get('flat')}，"
                        f"无有效行情 {breadth.get('noData')}")
        avg = breadth.get("avgChangePercent")
        if avg is not None:
            breadth_text += f"，平均涨跌幅 {avg:+.2f}%"
        breadth_text += "。"
        facts.append(breadth_text)
    if data.get("note"):
        facts.append(f"说明：{data['note']}")
    text = ("指数：" + "，".join(parts) + "。") if parts else ""
    return (text + breadth_text) or None, facts


def _recaps(data: dict[str, Any]) -> tuple[str | None, list[str]]:
    items = data.get("items") or []
    facts: list[str] = []
    if not items:
        return None, facts
    latest = items[0]
    text = f"最近一篇已确认复盘：{latest.get('recapDate')}《{latest.get('title')}》。"
    for item in items:
        facts.append(f"{item.get('recapDate')}《{item.get('title')}》（{item.get('status')}）："
                     f"{item.get('marketSummaryPreview') or '无摘要'}")
    return text, facts


def _diary(data: dict[str, Any]) -> tuple[str | None, list[str]]:
    hits = data.get("hits") or []
    facts: list[str] = []
    if not hits:
        return None, facts
    text = f"日记里命中 {len(hits)} 篇与「{data.get('keyword')}」相关的内容。"
    for hit in hits:
        facts.append(f"{hit.get('path')} / {hit.get('title')}：{hit.get('contentPreview') or '（无正文预览）'}")
    return text, facts


def _library(data: dict[str, Any]) -> tuple[str | None, list[str]]:
    hits = data.get("hits") or []
    facts: list[str] = []
    if not hits:
        # 没有命中就不给结论：这条回答不该由"模型知道的事"来填
        return None, facts
    titles = {hit.get("title") for hit in hits}
    text = (f"资料库命中 {len(hits)} 个片段，来自 {len(titles)} 篇资料"
            f"（关键词「{data.get('keyword')}」）。")
    for hit in hits:
        # citation 由 Java 按真实片段位置拼装，这里直接用，不在前端重新拼一遍
        facts.append(f"{hit.get('citation')}：{hit.get('snippet') or '（无内容）'}")
    return text, facts


_HANDLERS = {
    "positions": _positions,
    "watchlist-quotes": _watchlist,
    "alert-events": _alerts,
    "market-brief": _market_brief,
    "recaps": _recaps,
    "diary-search": _diary,
    "library-search": _library,
}


# ----------------------------------------------------------------- 格式化


def _name(item: dict[str, Any]) -> str:
    symbol = item.get("symbol") or "未知标的"
    name = item.get("name")
    return f"{symbol} {name}" if name else str(symbol)


def _dedupe(items: list[str]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for item in items:
        if item not in seen:
            seen.add(item)
            out.append(item)
    return out
