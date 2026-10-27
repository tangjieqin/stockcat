"""问题 → 工具计划。

P0 用**关键词规则**选工具，不让模型自己决定调什么。理由：
- P0 要验证的是"工具能取到数、回答能引用数据"，不是模型有多会规划；
- 规则可见、可测、零成本，出错能一眼看出是哪条规则；
- 链路稳定后把 :func:`plan` 换成 tool calling 即可，上层（service/composer）不用动。

规则按顺序累加、去重保序，最后截到 ``agent_max_tool_calls``。
"""
from __future__ import annotations

import re

from app.config import settings

# (关键词, 要调的工具, 工具参数)
_RULES: tuple[tuple[tuple[str, ...], str, dict], ...] = (
    (("持仓", "仓位", "盈亏", "成本", "账户", "浮盈", "套牢"), "positions", {}),
    (("自选", "行情", "涨", "跌", "报价", "价格"), "watchlist-quotes", {}),
    (("告警", "触发", "预警", "通知", "提醒"), "alert-events", {"limit": 5}),
    (("复盘",), "recaps", {"limit": 3}),
    (("复盘", "大盘", "指数", "市场", "板块", "行情"), "market-brief", {}),
)

DEFAULT_TOOLS: tuple[tuple[str, dict], ...] = (
    ("positions", {}),
    ("watchlist-quotes", {}),
    ("alert-events", {"limit": 5}),
)

# 提取日记关键词时要先剥掉的疑问/指代词（长词在前，避免"里面"被"里"切碎）
_DIARY_NOISE = (
    "我的日记", "日记里", "日记", "笔记", "里面", "里的", "关于", "有没有",
    "帮我", "看看", "看一下", "查一下", "搜索", "查找", "记录", "我的",
    "吗", "呢", "？", "?", "，", ",", "。", " ",
)


def plan(question: str) -> list[tuple[str, dict]]:
    """返回 [(工具名, 参数), ...]。"""
    text = question or ""
    planned: list[tuple[str, dict]] = []
    seen: set[str] = set()

    for keywords, tool, params in _RULES:
        if any(k in text for k in keywords) and tool not in seen:
            planned.append((tool, dict(params)))
            seen.add(tool)

    if "日记" in text or "笔记" in text:
        keyword = diary_keyword(text)
        if keyword and "diary-search" not in seen:
            planned.append(("diary-search", {"keyword": keyword, "limit": 3}))
            seen.add("diary-search")

    if not planned:
        planned = [(tool, dict(params)) for tool, params in DEFAULT_TOOLS]

    return planned[: max(1, settings.agent_max_tool_calls)]


def diary_keyword(question: str) -> str | None:
    """从"我日记里关于白酒的记录"里剥出"白酒"。剥不出有效词就不查日记，别拿整句去搜。"""
    text = question
    for noise in _DIARY_NOISE:
        text = text.replace(noise, " ")
    words = [w.strip("的了是有吗呢") for w in re.split(r"\s+", text)]
    words = [w for w in words if len(w) >= 2]
    return words[0] if words else None
