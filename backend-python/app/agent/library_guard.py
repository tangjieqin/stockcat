"""资料引用的硬约束（P3.3）。

问「我存的资料里关于渠道库存怎么说」时，模型拿到的是若干**片段**，它可以写得像模像样，
也可以顺手写一个「《某某研报》」——而库里根本没有这篇。这种错误比数字写错更难发现：
数字对不上还能靠人对账，出处编得像真的就只能靠系统拦。

所以引用只认 ``library-search`` 返回的 ``citation``（后端按真实 ``chunkId``/``seq`` 拼装）：

1. 出现命中列表以外的「《标题》」，那一段整段删除，并换成实际命中清单；**不猜、不保留半句**。
2. 标题对但段号对不上（「《访谈纪要》· 第 9 段」而这篇只有 3 段）同样算编——段号也是出处。
3. 被删过东西就必须说出来，写在 limits 里：用户有权知道回答被裁剪过。
"""
from __future__ import annotations

import re
from typing import Any

_TITLE_RE = re.compile(r"《([^《》]{1,80})》")
# 「《标题》· 第 3 段」「《标题》第3段」都认；段号缺失时只校验标题
_SECTION_RE = re.compile(r"《([^《》]{1,80})》\s*[·・·\-—]?\s*第\s*(\d{1,3})\s*段")
_SENTENCE_SPLIT = re.compile(r"(?<=[。！？；])")

CITATION_LIMIT_NOTE = "模型给出的出处不在本次资料命中列表里，相关表述已删除；只保留可核对的出处。"


def enforce(answer: dict[str, Any], results: list[dict[str, Any]]) -> tuple[dict[str, Any], list[str]]:
    """按命中列表清洗模型回答，返回 ``(新 answer, 追加的 limits)``。

    没有调用资料工具时原样返回——这条约束只管资料，不干涉其它场景。
    """
    library = _library_result(results)
    if library is None or not library.get("ok"):
        return answer, []

    hits = _hits(library)
    if not hits:
        # 零命中正常走不到这里（service 直接降级成规则回答），但"成功但空"仍可能发生：
        # 此时任何出处都不可信，一并抹掉。
        if not _TITLE_RE.search(_answer_text(answer)):
            return answer, []
        answer, _ = _strip(answer, set(), set())
        answer["conclusion"] = answer.get("conclusion") or "资料库里没有找到相关资料，未使用模型生成结论。"
        return answer, [f"{CITATION_LIMIT_NOTE}（本次没有任何资料命中）"]

    allowed_titles = {str(h.get("title")) for h in hits if h.get("title")}
    allowed_pairs = {
        (str(h.get("title")), str(h.get("seq")))
        for h in hits if h.get("title") and h.get("seq") is not None
    }
    bad = _bad_citations(answer, allowed_titles, allowed_pairs)
    if not bad:
        return answer, []

    # 按"允许清单"裁剪，而不是按"坏清单"：同名标题的第 2 段是好的、第 9 段是编的，
    # 只删第 9 段那一句，不能把这篇资料的所有引用都干掉。
    answer, _ = _strip(answer, allowed_titles, allowed_pairs)

    # 抹掉之后不能留一个空壳：把真实命中清单补进数据依据，用户还能顺着它去看原文
    canon = "；".join(citations_of(hits)[:5])
    if canon:
        answer["evidence"] = list(answer.get("evidence") or []) + [f"资料实际命中：{canon}"]
    if not answer.get("conclusion"):
        answer["conclusion"] = f"本次只保留可核对的资料出处，命中 {len(hits)} 段，详见数据依据。"

    shown = "、".join(sorted({f"《{t}》" for t, _ in bad})[:3])
    return answer, [f"{CITATION_LIMIT_NOTE}（已删除：{shown}）"]


def citations_of(hits: list[dict[str, Any]]) -> list[str]:
    """命中的引用文本，去重保序。"""
    out: list[str] = []
    seen: set[str] = set()
    for hit in hits:
        text = hit.get("citation")
        if not text or text in seen:
            continue
        seen.add(text)
        out.append(text)
    return out


def pick_citations(answer: dict[str, Any], results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """回答里实际引用了哪些片段；一条都没对上（规则模式常见）时给全部命中。

    "全部命中 → 视为都被引用"是有意的：规则模式的依据就是这些片段，
    列表里不出现它们，用户反而不知道为什么会有这条回答。
    """
    library = _library_result(results)
    if library is None or not library.get("ok"):
        return []
    hits = _hits(library)
    if not hits:
        return []

    text = _answer_text(answer)
    picked = [h for h in hits if h.get("citation") and str(h["citation"]) in text] or hits
    out: list[dict[str, Any]] = []
    seen: set[str] = set()
    for hit in picked:
        key = str(hit.get("citation"))
        if key in seen:
            continue
        seen.add(key)
        out.append({
            "chunkId": hit.get("chunkId"),
            "documentId": hit.get("documentId"),
            "title": hit.get("title"),
            "seq": hit.get("seq"),
            "citation": hit.get("citation"),
        })
    return out


# ----------------------------------------------------------------- 内部


def _library_result(results: list[dict[str, Any]]) -> dict[str, Any] | None:
    for result in results or []:
        if result.get("tool") == "library-search":
            return result
    return None


def _hits(library: dict[str, Any]) -> list[dict[str, Any]]:
    data = (library.get("data") or {}).get("data") or {}
    hits = data.get("hits")
    return [h for h in hits or [] if isinstance(h, dict)]


def _answer_text(answer: dict[str, Any]) -> str:
    parts = [str(answer.get("conclusion") or "")]
    parts.extend(str(item) for item in answer.get("evidence") or [])
    return "\n".join(parts)


def _bad_citations(answer: dict[str, Any], titles: set[str], pairs: set[tuple[str, str]]) -> list[tuple[str, str | None]]:
    """返回 [(标题, 段号)]；段号 None 表示"标题就不存在"。"""
    bad: list[tuple[str, str | None]] = []
    text = _answer_text(answer)
    for match in _SECTION_RE.finditer(text):
        title, seq = match.group(1).strip(), match.group(2)
        if title not in titles or (title, seq) not in pairs:
            bad.append((title, seq))
    for match in _TITLE_RE.finditer(text):
        title = match.group(1).strip()
        if title not in titles:
            bad.append((title, None))
    return _dedupe(bad)


def _strip(answer: dict[str, Any], allowed_titles: set[str], allowed_pairs: set[tuple[str, str]]) -> tuple[dict[str, Any], bool]:
    """删掉带未知出处的句子与依据行；一句里只要有一处对不上，整句不要。"""
    removed = False

    conclusion = str(answer.get("conclusion") or "")
    sentences = [s for s in _SENTENCE_SPLIT.split(conclusion) if s]
    kept = [s for s in sentences if not _offends(s, allowed_titles, allowed_pairs)]
    removed = removed or len(kept) != len(sentences)

    evidence = []
    for line in answer.get("evidence") or []:
        if _offends(str(line), allowed_titles, allowed_pairs):
            removed = True
            continue
        evidence.append(line)

    return {**answer, "conclusion": "".join(kept).strip(), "evidence": evidence}, removed


def _offends(text: str, allowed_titles: set[str], allowed_pairs: set[tuple[str, str]]) -> bool:
    for match in _SECTION_RE.finditer(text):
        title, seq = match.group(1).strip(), match.group(2)
        if title not in allowed_titles or (title, seq) not in allowed_pairs:
            return True
    return any(m.group(1).strip() not in allowed_titles for m in _TITLE_RE.finditer(text))


def _dedupe(items: list[tuple[str, str | None]]) -> list[tuple[str, str | None]]:
    seen: set[tuple[str, str | None]] = set()
    out: list[tuple[str, str | None]] = []
    for item in items:
        if item not in seen:
            seen.add(item)
            out.append(item)
    return out
