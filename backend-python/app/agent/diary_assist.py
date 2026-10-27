"""日记辅助（P2 场景四）：结构化整理 + 历史回顾。

与其它 AI 场景同一条底线：**只读、不落库**。整理结果只是建议稿，写不写回正文由用户决定——
AI 不改用户已经写下的东西，这一条比"整理得好不好"重要。

额外一条硬约束：涉及的标的必须落在 Java 给出的**已知标的白名单**里（自选 + 持仓）。
模型编一个不存在的代码，在页面上和真的没有区别，比写错一个数字更难被发现。
越界的标的一律丢弃，并写进 limitations 让人看见。
"""
from __future__ import annotations

import json
import re
from typing import Any

_SENT_SPLIT = re.compile(r"[。！？；\n]+")
_WS = re.compile(r"\s+")

# 出现这些词说明是一句"操作或计划"，比单纯的描述更值得进要点
_ACTION_WORDS = ("买", "卖", "加仓", "减仓", "清仓", "建仓", "止损", "止盈", "换仓")
_FOLLOW_WORDS = (
    "待", "明天", "下周", "下月", "接下来", "关注", "看看", "观察", "计划",
    "准备", "需要", "确认", "复盘", "如果", "等到",
)
_DIGIT = re.compile(r"\d")

MAX_HIGHLIGHTS = 6
MAX_FOLLOW_UPS = 5
MAX_TEXT_CHARS = 6000

BODY_LIMITATION = (
    "整理结果只基于这篇日记的原文，未引用任何行情、持仓或告警数据；"
    "文中提到的盈亏、成本与仓位没有和交易流水核对过。"
)
REVIEW_LIMITATION = (
    "回顾只在日记正文里做关键词检索，未命中的记录不会被纳入；"
    "因此总结只代表命中篇目的内容，不代表全部历史。"
)


def extract(title: str, content: str, known_instruments: list[dict[str, Any]]) -> dict[str, Any]:
    """规则提取：不改写正文，只做「找出什么」这类不依赖模型也能做对的事。"""
    text = content or ""
    sentences = _sentences(text)
    highlights = _highlights(sentences)
    follow_ups = [s for s in sentences if any(w in s for w in _FOLLOW_WORDS)][:MAX_FOLLOW_UPS]
    instruments = match_instruments(text, known_instruments)

    return {
        "summary": _summary(title, sentences, instruments),
        "highlights": highlights,
        "instruments": instruments,
        "followUps": follow_ups,
        # 规则模式不提供改写稿：把原文换个说法不是"整理"，只会让人以为 AI 读过一遍
        "refined": None,
    }


def match_instruments(text: str, known_instruments: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """只在已知标的里找，绝不凭"看起来像代码"猜。"""
    found: list[dict[str, Any]] = []
    seen: set[str] = set()
    for item in known_instruments or []:
        symbol = str(item.get("symbol") or "").strip()
        name = (item.get("name") or "").strip() or None
        if not symbol or symbol in seen:
            continue
        if symbol in text or (name and name in text):
            seen.add(symbol)
            found.append({"symbol": symbol, "name": name})
    return found


def refine_prompt(title: str, content: str, known_instruments: list[dict[str, Any]]) -> str:
    whitelist = json.dumps(known_instruments or [], ensure_ascii=False)
    return (
        f"日记标题：{title or '（无标题）'}\n\n"
        f"正文（最多取前 {MAX_TEXT_CHARS} 字）：\n{content[:MAX_TEXT_CHARS]}\n\n"
        f"已知标的白名单（symbol 与 name 只能从这里取）：{whitelist}\n\n"
        "请整理这篇日记，输出 JSON。"
    )


def accept_refine(view: Any, known_instruments: list[dict[str, Any]]) -> dict[str, Any] | None:
    """校验模型输出。任何一段不合格就整份退回规则提取，不拿半截结果凑数。"""
    if not isinstance(view, dict):
        return None
    summary = _clean(view.get("summary"))
    highlights = _str_list(view.get("highlights"), MAX_HIGHLIGHTS)
    follow_ups = _str_list(view.get("followUps"), MAX_FOLLOW_UPS)
    refined = _clean(view.get("refined"))
    if not summary or not highlights or not refined:
        return None

    allowed = {
        str(item.get("symbol")): item.get("name")
        for item in (known_instruments or [])
        if item.get("symbol")
    }
    instruments: list[dict[str, Any]] = []
    dropped: list[str] = []
    for raw in view.get("instruments") or []:
        symbol = str((raw or {}).get("symbol") or "").strip()
        if not symbol:
            continue
        if symbol in allowed:
            instruments.append({"symbol": symbol, "name": allowed[symbol]})
        else:
            dropped.append(symbol)

    return {
        "summary": summary,
        "highlights": highlights,
        "instruments": instruments,
        "followUps": follow_ups,
        "refined": refined,
        "droppedInstruments": dropped,
    }


def review_prompt(keyword: str, hits: list[dict[str, Any]]) -> str:
    lines = []
    for item in hits:
        path = _path_text(item.get("path")) or "（根目录）"
        lines.append(
            f"- 《{item.get('title')}》 路径：{path} 日期：{_date_text(item.get('updatedAt')) or '未记录'}\n"
            f"  片段：{item.get('preview') or '（无正文）'}"
        )
    return (
        f"回顾关键词：{keyword}\n\n"
        f"检索命中的日记（共 {len(hits)} 篇，按时间倒序）：\n" + "\n".join(lines) + "\n\n"
        "请据此写一段回顾，输出 JSON。"
    )


def accept_review(view: Any, hits: list[dict[str, Any]]) -> dict[str, Any] | None:
    """校验模型输出，并把引用限定在真实命中的标题上。"""
    if not isinstance(view, dict):
        return None
    theme = _clean(view.get("theme"))
    if not theme:
        return None
    titles = {str(item.get("title") or "") for item in hits}
    by_title = {str(item.get("title") or ""): item for item in hits}

    threads: list[dict[str, Any]] = []
    for raw in view.get("threads") or []:
        if not isinstance(raw, dict):
            continue
        title = _clean(raw.get("title"))
        if not title or title not in titles:
            continue
        item = by_title[title]
        threads.append({
            "title": title,
            "date": _date_text(item.get("updatedAt")),
            "path": _path_text(item.get("path")),
            "point": _clean(raw.get("point")),
        })

    # 引用只认命中列表里的标题；模型一条都对不上时，用命中列表自己拼（宁可少，不可编）
    raw_citations = _str_list(view.get("citations"), 8)
    citations = [c for c in raw_citations if any(t and t in c for t in titles)]
    if not citations:
        citations = [
            f"《{item.get('title')}》{_date_text(item.get('updatedAt')) or ''}".strip()
            for item in hits[:5]
        ]

    return {
        "theme": theme,
        "threads": threads,
        "recurring": _str_list(view.get("recurring"), 5),
        "gaps": _str_list(view.get("gaps"), 5),
        "citations": citations,
    }


def _sentences(text: str) -> list[str]:
    out: list[str] = []
    seen: set[str] = set()
    for raw in _SENT_SPLIT.split(text or ""):
        line = _WS.sub(" ", raw).strip(" -·、,，. ")
        if len(line) < 4 or line in seen:
            continue
        seen.add(line)
        out.append(line)
    return out


def _highlights(sentences: list[str]) -> list[str]:
    """优先取带数字或操作词的句子：日记里最值得回看的通常是「买了多少」「打算做什么」。"""
    scored = []
    for index, line in enumerate(sentences):
        score = 0
        if _DIGIT.search(line):
            score += 2
        if any(word in line for word in _ACTION_WORDS):
            score += 2
        if any(word in line for word in _FOLLOW_WORDS):
            score += 1
        scored.append((score, -index, line))
    scored.sort(reverse=True)
    picked = [line for score, _, line in scored if score > 0][:MAX_HIGHLIGHTS]
    # 全是没有特征词的流水句时，退回原文前几句，而不是给一个空列表
    return picked or sentences[:MAX_HIGHLIGHTS]


def _summary(title: str, sentences: list[str], instruments: list[dict[str, Any]]) -> str:
    head = sentences[0] if sentences else ""
    if len(head) > 60:
        head = head[:60] + "…"
    parts = [f"《{title}》" if title else "本篇日记"]
    if head:
        parts.append(head)
    if instruments:
        names = "、".join(f"{i.get('symbol')} {i.get('name') or ''}".strip() for i in instruments[:3])
        parts.append(f"涉及 {names}")
    return "；".join(parts)


def _clean(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    text = _WS.sub(" ", value).strip()
    return text or None


def _path_text(raw: Any) -> str:
    """路径在 Java 侧是「父 / 子」形式的字符串，这里兼容 list。"""
    if isinstance(raw, list):
        return " / ".join(str(part) for part in raw if part)
    return str(raw or "").strip()


def _date_text(raw: Any) -> str | None:
    """只取日期部分：回顾关心「哪天写的」，精确到秒反而难读。"""
    text = str(raw or "").strip()
    return text[:10] if len(text) >= 10 else (text or None)


def _str_list(value: Any, limit: int) -> list[str]:
    if not isinstance(value, list):
        return []
    out: list[str] = []
    for raw in value:
        text = _clean(raw)
        if text and text not in out:
            out.append(text)
        if len(out) >= limit:
            break
    return out
