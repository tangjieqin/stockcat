"""每日复盘草稿的「事实底稿」（P2.3）。

两种模式共用同一份底稿：
- 未配置模型 → 直接用底稿作为草稿（mode=rule_based），朴素但每句话都有数据来源；
- 配置了模型 → 底稿作为 prompt 的唯一数据源，模型只负责组织语言与展开叙述。

四条硬约束（同时写进 prompt 与本文件实现）：
1. 数值只能来自工具返回，缺数据就写「数据缺失」，**禁止推测、禁止补 0**；
2. 本项目没有采集行业板块/资金流数据，"板块表现"只能写自选分组汇总，并明确说明这不是全市场板块；
3. 不给买卖建议、目标价、点位预测，只描述已发生的事实与变化；
4. 行情是采集时点快照，不能写成"收盘价"，数据截至时间必须出现在正文里。

分工与告警解释一致：``limitations`` 永远由本模块按工具返回生成，不接受模型"补充"或"精简"——
数据限制一旦能被润色，就会出现"模型觉得不重要就删掉"的情况。
"""
from __future__ import annotations

import json
from typing import Any

from app.agent.formatting import cn_time, money, pct, price, signed

MAX_TITLE_CHARS = 128  # market_recap.title 是 VARCHAR(128)

SECTOR_DATA_MISSING = "本项目未采集行业板块与资金流数据，板块表现只按自选分组汇总（非行业板块），不代表全市场板块涨跌。"
QUOTE_SNAPSHOT_NOTE = "指数与自选行情是采集时点快照，不是交易所官方收盘价。"
AI_DRAFT_NOTE = "本文由 AI 依据上述数据生成，未经人工确认前不计入正式复盘。"


def compose(date: str, results: list[dict[str, Any]]) -> dict[str, Any]:
    """返回 ``{draft, limitations, hasMaterial}``。

    ``hasMaterial=False`` 表示连一段可陈述的事实都没有（指数、自选、持仓全空），
    上层应报 NO_DATA 而不是让模型凭空写一篇复盘。
    """
    by_tool = {result["tool"]: result for result in results if result.get("ok")}
    brief = _data_of(by_tool.get("market-brief"))
    positions = _data_of(by_tool.get("positions"))
    watchlist = _data_of(by_tool.get("watchlist-quotes"))
    alerts = _data_of(by_tool.get("alert-events"))
    recaps = _data_of(by_tool.get("recaps"))

    indices = brief.get("indices") or []
    breadth = brief.get("watchlistBreadth") or {}
    quotes = watchlist.get("items") or []
    position_items = positions.get("items") or []

    limitations = _collect_limitations(results, indices, breadth)
    has_material = bool(indices) or bool(breadth.get("total")) or bool(position_items)

    market_summary = _market_summary(indices, breadth)
    sector_notes = _sector_notes(quotes)
    content = _content(date, indices, breadth, quotes, positions, alerts, recaps, limitations)

    return {
        "draft": {
            "title": _title(date),
            "marketSummary": market_summary,
            "sectorNotes": sector_notes,
            "content": content,
        },
        "limitations": limitations,
        "hasMaterial": has_material,
    }


def llm_prompt(date: str, results: list[dict[str, Any]], limitations: list[str]) -> str:
    """把底稿数据交给模型：只给事实，不给结论，也不给"参考写法"。"""
    blocks = []
    for result in results:
        if not result.get("ok"):
            blocks.append(f"[{result['tool']}] 调用失败：{result.get('error')}")
            continue
        body = result.get("data") or {}
        blocks.append(
            f"[{result['tool']}] dataAsOf={body.get('dataAsOf')}\n"
            f"缺失项={_json(body.get('missing') or [])}\n"
            f"数据={_json(_compact(body.get('data')))}"
        )
    return (
        f"复盘归属日期：{date}（Asia/Shanghai）\n"
        f"已知数据限制（这些限制必须原样体现在正文里，不要弱化）：\n"
        + "\n".join(f"- {item}" for item in limitations)
        + "\n\n业务数据（唯一事实来源）：\n"
        + "\n\n".join(blocks)
    )


# ------------------------------------------------------------------ 各分区文案


def _market_summary(indices: list[dict[str, Any]], breadth: dict[str, Any]) -> str:
    parts: list[str] = []
    index_parts = [
        f"{index.get('name') or index.get('symbol')} {money(index.get('lastPrice')) or '—'}"
        f"（{pct(index.get('changePercent')) or '涨跌幅缺失'}）"
        for index in indices
    ]
    if index_parts:
        parts.append("指数快照：" + "、".join(index_parts) + "。")
    else:
        parts.append("本次没有取到指数行情快照，大盘点位与涨跌幅无法给出。")

    if breadth.get("total"):
        text = (f"自选 {breadth.get('total')} 只：涨 {breadth.get('up')}、跌 {breadth.get('down')}、"
                f"平 {breadth.get('flat')}，无有效行情 {breadth.get('noData')}")
        if breadth.get("avgChangePercent") is not None:
            text += f"，有效行情平均涨跌幅 {pct(breadth.get('avgChangePercent'))}"
        parts.append(text + "。")
    else:
        parts.append("自选列表为空，没有可统计的涨跌分布。")
    return "".join(parts)


def _sector_notes(quotes: list[dict[str, Any]]) -> str:
    groups = _group_stats(quotes)
    if not groups:
        return "没有可用行情，无法给出自选分组表现。" + SECTOR_DATA_MISSING
    parts = []
    for group in groups:
        avg = pct(group["avgChangePercent"]) if group["avgChangePercent"] is not None else "涨跌幅缺失"
        parts.append(f"{group['groupName']}（{group['count']} 只）平均 {avg}")
    return "自选分组表现：" + "；".join(parts) + "。" + SECTOR_DATA_MISSING


def _content(date: str,
             indices: list[dict[str, Any]],
             breadth: dict[str, Any],
             quotes: list[dict[str, Any]],
             positions: dict[str, Any],
             alerts: dict[str, Any],
             recaps: dict[str, Any],
             limitations: list[str]) -> str:
    lines: list[str] = [f"# {date} 复盘（数据摘要）", ""]

    lines += ["## 大盘", ""]
    if indices:
        for index in indices:
            lines.append(
                f"- {index.get('name') or index.get('symbol')}："
                f"{money(index.get('lastPrice')) or '—'}，{pct(index.get('changePercent')) or '涨跌幅缺失'}"
                f"（行情时间 {cn_time(index.get('marketTime'))}）"
            )
    else:
        lines.append("- 本次未取到指数行情快照，大盘点位与涨跌幅无法给出。")
    lines.append("")

    lines += ["## 自选表现", ""]
    if breadth.get("total"):
        line = (f"- 共 {breadth.get('total')} 只：涨 {breadth.get('up')}、跌 {breadth.get('down')}、"
                f"平 {breadth.get('flat')}，无有效行情 {breadth.get('noData')}")
        if breadth.get("avgChangePercent") is not None:
            line += f"，平均涨跌幅 {pct(breadth.get('avgChangePercent'))}"
        lines.append(line + "。")
        up, down = _extremes(quotes)
        if up:
            lines.append(f"- 领涨：{up}")
        if down:
            lines.append(f"- 领跌：{down}")
        for group in _group_stats(quotes):
            avg = pct(group["avgChangePercent"]) if group["avgChangePercent"] is not None else "涨跌幅缺失"
            lines.append(f"- {group['groupName']}：{group['count']} 只，平均 {avg}")
        lines.append(f"- {SECTOR_DATA_MISSING}")
    else:
        lines.append("- 自选列表为空，或没有有效行情，无法统计涨跌分布。")
    lines.append("")

    lines += ["## 持仓", ""]
    if positions.get("count"):
        summary = f"- 持有 {positions.get('count')} 只"
        if positions.get("totalMarketValue") is not None:
            summary += f"，市值合计 {money(positions.get('totalMarketValue'))} 元"
        if positions.get("totalUnrealizedPnl") is not None:
            summary += f"，浮动盈亏 {signed(positions.get('totalUnrealizedPnl'))} 元"
        if positions.get("totalRealizedPnl") is not None:
            summary += f"，累计已实现 {signed(positions.get('totalRealizedPnl'))} 元"
        lines.append(summary + "。")
        if positions.get("priceComplete") is False:
            lines.append(
                f"- 其中 {positions.get('pricedCount')} 只取到有效价格，"
                "汇总值为「能取到价的部分」，不是全组合市值。"
            )
        for item in (positions.get("items") or [])[:8]:
            name = f"{item.get('symbol')} {item.get('name') or ''}".strip()
            lines.append(
                f"- {name}：{price(item.get('avgCost')) or '成本缺失'} 成本 / "
                f"{price(item.get('lastPrice')) or '现价缺失'} 现价，"
                f"浮盈 {signed(item.get('unrealizedPnl')) or '—'}"
                f"（{item.get('priceStatus')}）"
            )
    else:
        lines.append("- 当前没有持仓记录。")
    lines.append("")

    lines += ["## 告警", ""]
    if alerts.get("count"):
        lines.append(f"- 最近触发 {alerts.get('count')} 条（不限当日）：")
        for event in (alerts.get("items") or [])[:5]:
            lines.append(
                f"  - {cn_time(event.get('triggeredAt'))} {event.get('symbol')} "
                f"「{event.get('ruleName')}」，触发值 {money(event.get('observedPrice')) or '—'}"
            )
    else:
        lines.append("- 最近没有触发告警事件。")
    lines.append("")

    if recaps.get("count"):
        latest = (recaps.get("items") or [])[0]
        lines += ["## 前情", "",
                  f"- 上一篇已确认复盘：{latest.get('recapDate')}《{latest.get('title')}》"
                  f"{'—— ' + latest['marketSummaryPreview'] if latest.get('marketSummaryPreview') else ''}",
                  ""]

    lines += ["## 数据说明", ""]
    lines += [f"- {item}" for item in limitations]
    return "\n".join(lines)


# ------------------------------------------------------------------ 小工具


def _collect_limitations(results: list[dict[str, Any]],
                         indices: list[dict[str, Any]],
                         breadth: dict[str, Any]) -> list[str]:
    limits: list[str] = []
    for result in results:
        if not result.get("ok"):
            limits.append(f"{result['tool']} 调用失败：{result.get('error')}")
            continue
        for item in (result.get("data") or {}).get("missing") or []:
            limits.append(item)
    if indices:
        limits.append(QUOTE_SNAPSHOT_NOTE)
    if not breadth.get("total"):
        limits.append("自选列表为空或无有效行情，自选涨跌分布不可用")
    limits.append(SECTOR_DATA_MISSING)
    limits.append(AI_DRAFT_NOTE)
    return _dedupe(limits)


def _group_stats(quotes: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """按自选分组汇总涨跌幅。只认 VALID 且有涨跌幅的行情，缺失不参与均值。"""
    buckets: dict[str, list[float]] = {}
    for item in quotes:
        if item.get("dataStatus") != "VALID" or item.get("changePercent") is None:
            continue
        group = item.get("groupName") or "自选"
        buckets.setdefault(group, []).append(float(item["changePercent"]))
    stats = []
    for group, values in sorted(buckets.items(), key=lambda kv: -sum(kv[1]) / len(kv[1])):
        stats.append({
            "groupName": group,
            "count": len(values),
            "avgChangePercent": round(sum(values) / len(values), 4),
        })
    return stats


def _extremes(quotes: list[dict[str, Any]]) -> tuple[str | None, str | None]:
    """领涨/领跌。只有一个有效标的时两者会是同一只，那就都不写——那是噪声不是信息。"""
    valid = [i for i in quotes if i.get("dataStatus") == "VALID" and i.get("changePercent") is not None]
    if len(valid) < 2:
        return None, None
    best = max(valid, key=lambda i: i["changePercent"])
    worst = min(valid, key=lambda i: i["changePercent"])
    return _quote_text(best), _quote_text(worst)


def _quote_text(item: dict[str, Any]) -> str:
    name = f"{item.get('symbol')} {item.get('name') or ''}".strip()
    return f"{name} {price(item.get('lastPrice')) or '—'}（{pct(item.get('changePercent')) or '—'}）"


def _data_of(result: dict[str, Any] | None) -> dict[str, Any]:
    if not result:
        return {}
    return ((result.get("data") or {}).get("data")) or {}


def _title(date: str) -> str:
    return f"{date} 复盘（AI 草稿）"[:MAX_TITLE_CHARS]


def _compact(data: Any, depth: int = 0) -> Any:
    """列表只留前几条：够写一篇复盘即可，别把整库塞进 prompt。"""
    if isinstance(data, dict):
        return {key: _compact(value, depth + 1) for key, value in data.items()}
    if isinstance(data, list):
        return [_compact(item, depth + 1) for item in data[:6]]
    return data


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False)


def _dedupe(items: list[str]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for item in items:
        if item and item not in seen:
            seen.add(item)
            out.append(item)
    return out
