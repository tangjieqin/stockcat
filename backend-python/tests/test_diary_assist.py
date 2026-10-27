"""日记辅助：整理不落库、回顾不编造、标的不越界。"""
import pytest
from fastapi.testclient import TestClient

from app.agent import diary_assist, llm, service
from app.main import app

client = TestClient(app)

KNOWN = [
    {"symbol": "510300", "name": "沪深300ETF"},
    {"symbol": "600519", "name": "贵州茅台"},
]

BODY = (
    "今天买了 510300 一手，成本 4.105。\n"
    "明天关注 600519 的回调，如果跌破 1500 就再看看。\n"
    "顺手记个 000001 的新闻，虽然没持仓。\n"
    "这周整体回撤有点大，心态还行。"
)


# ------------------------------------------------------------------ 规则提取


def test_extract_only_accepts_known_instruments():
    result = diary_assist.extract("周记", BODY, KNOWN)
    symbols = [item["symbol"] for item in result["instruments"]]

    assert symbols == ["510300", "600519"]
    # 000001 文中明明出现了，但不在白名单：宁可漏，不可编
    assert "000001" not in symbols


def test_extract_highlights_prefer_numbers_and_actions():
    result = diary_assist.extract("周记", BODY, KNOWN)

    assert any("510300" in line for line in result["highlights"])
    assert result["followUps"]
    assert any("成交" in line or "关注" in line for line in result["followUps"])


def test_extract_never_rewrites_body_in_rule_mode():
    result = diary_assist.extract("周记", BODY, KNOWN)
    # 规则模式不提供改写稿：换个说法不等于整理
    assert result["refined"] is None


def test_extract_falls_back_to_first_sentences_when_nothing_scores():
    result = diary_assist.extract("杂记", "天气不错。随便走走。看了会儿盘。", KNOWN)
    assert result["highlights"]


# ------------------------------------------------------------------ 模型输出校验


def test_accept_refine_drops_instruments_outside_whitelist():
    view = {
        "summary": "买了 ETF，明天看茅台",
        "highlights": ["买入 510300"],
        "instruments": [
            {"symbol": "510300", "name": "沪深300ETF"},
            {"symbol": "000001", "name": "平安银行"},
        ],
        "followUps": ["明天看茅台回调"],
        "refined": "## 操作\n- 买入 510300",
    }

    accepted = diary_assist.accept_refine(view, KNOWN)

    assert accepted is not None
    assert [item["symbol"] for item in accepted["instruments"]] == ["510300"]
    assert accepted["droppedInstruments"] == ["000001"]


def test_accept_refine_requires_all_three_parts():
    partial = {"summary": "有摘要", "highlights": [], "followUps": [], "refined": "正文"}

    # 缺 highlights 就整份退回规则提取，不拿半截结果凑数
    assert diary_assist.accept_refine(partial, KNOWN) is None
    assert diary_assist.accept_refine({"summary": "有摘要", "highlights": ["a"]}, KNOWN) is None


def test_accept_review_limits_threads_and_citations_to_hits():
    hits = [
        {"title": "十月第一周", "updatedAt": "2026-10-05T10:00:00+08:00", "path": "投资 / 周记"},
        {"title": "十月第二周", "updatedAt": "2026-10-12T10:00:00+08:00", "path": "投资 / 周记"},
    ]
    view = {
        "theme": "连续两周在减仓",
        "threads": [
            {"title": "十月第一周", "point": "开始减仓"},
            {"title": "九月总结", "point": "这条不在命中列表里"},
        ],
        "recurring": ["减仓"],
        "gaps": ["没写减仓原因"],
        "citations": ["《十月第一周》2026-10-05", "《九月总结》2026-09-30"],
    }

    accepted = diary_assist.accept_review(view, hits)

    assert accepted is not None
    assert [t["title"] for t in accepted["threads"]] == ["十月第一周"]
    assert accepted["citations"] == ["《十月第一周》2026-10-05"]


def test_accept_review_builds_citations_from_hits_when_model_gives_none():
    hits = [{"title": "十月第一周", "updatedAt": "2026-10-05T10:00:00+08:00", "path": ""}]
    accepted = diary_assist.accept_review({"theme": "减仓"}, hits)

    assert accepted is not None
    assert accepted["citations"] == ["《十月第一周》2026-10-05"]


# ------------------------------------------------------------------ 接口


def test_refine_rejects_too_short_body():
    resp = client.post("/agent/v1/diary/refine", json={"title": "x", "content": "短"})

    assert resp.status_code == 400
    assert resp.json()["code"] == "VALIDATION_ERROR"


def test_refine_without_model_returns_rule_extraction(monkeypatch):
    monkeypatch.setattr(llm, "is_configured", lambda: False)

    resp = client.post(
        "/agent/v1/diary/refine",
        json={"title": "周记", "content": BODY, "knownInstruments": KNOWN},
    )

    body = resp.json()
    assert resp.status_code == 200
    assert body["mode"] == "rule_based"
    assert body["result"]["refined"] is None
    assert body["knownInstrumentCount"] == 2
    # 降级必须说明白，否则用户会以为这就是模型整理的结果
    assert any("未配置模型" in line for line in body["degraded"])
    assert any("未引用任何行情" in line for line in body["limitations"])


def test_refine_reports_truncation_when_model_path_clips_body(monkeypatch):
    """模型只看前 6000 字，超过了就必须在 limitations 里说出来。"""
    long_body = "白酒仓位继续减。渠道库存还没消化完。" * 400
    assert len(long_body) > diary_assist.MAX_TEXT_CHARS
    monkeypatch.setattr(llm, "is_configured", lambda: True)
    monkeypatch.setattr(llm, "refine_diary", lambda prompt: {
        "ok": True,
        "view": {
            "summary": "继续减仓",
            "highlights": ["渠道库存未消化"],
            "instruments": [],
            "followUps": [],
            "refined": "整理稿",
        },
        "error": None,
        "usage": {"model": "test-model", "promptTokens": 100, "completionTokens": 50},
        "elapsedMs": 300,
    })

    resp = client.post(
        "/agent/v1/diary/refine",
        json={"title": "长文", "content": long_body, "knownInstruments": KNOWN},
    )

    body = resp.json()
    assert body["mode"] == "llm"
    assert any("只整理了前" in line for line in body["limitations"])


def test_refine_rule_mode_does_not_claim_truncation(monkeypatch):
    """规则提取读的是全文，不能凭空说"只整理了前 N 字"。"""
    long_body = "白酒仓位继续减。渠道库存还没消化完。" * 400
    monkeypatch.setattr(llm, "is_configured", lambda: False)

    resp = client.post(
        "/agent/v1/diary/refine",
        json={"title": "长文", "content": long_body, "knownInstruments": KNOWN},
    )

    assert not any("只整理了前" in line for line in resp.json()["limitations"])


def _tool_result(hits):
    return [{
        "tool": "diary-search",
        "ok": True,
        "error": None,
        "elapsedMs": 8,
        "data": {
            "tool": "diary-search",
            "dataAsOf": None,
            "data": {"keyword": "减仓", "count": len(hits), "hits": hits},
            "missing": [],
        },
    }]


def test_review_without_hits_does_not_call_model(monkeypatch):
    monkeypatch.setattr(service, "run_tools", lambda planned: _tool_result([]))
    monkeypatch.setattr(llm, "is_configured", lambda: True)

    def boom(*_args, **_kwargs):
        raise AssertionError("没有命中时不该调用模型")

    monkeypatch.setattr(llm, "review_diary", boom)

    resp = client.post("/agent/v1/diary/review", json={"keyword": "减仓"})

    body = resp.json()
    assert resp.status_code == 200
    assert body["empty"] is True
    assert body["hitCount"] == 0
    assert body["theme"] is None
    assert body["citations"] == []


def test_review_with_hits_uses_model_summary(monkeypatch):
    hits = [{
        "nodeId": 7,
        "title": "十月第二周",
        "path": "投资 / 周记",
        "contentPreview": "继续减仓，理由是先保住今年的收益。",
        "updatedAt": "2026-10-12T10:00:00+08:00",
    }]
    monkeypatch.setattr(service, "run_tools", lambda planned: _tool_result(hits))
    monkeypatch.setattr(llm, "is_configured", lambda: True)
    monkeypatch.setattr(llm, "review_diary", lambda prompt: {
        "ok": True,
        "view": {
            "theme": "持续减仓",
            "threads": [{"title": "十月第二周", "point": "先保住今年收益"}],
            "recurring": ["减仓"],
            "gaps": [],
            "citations": ["《十月第二周》2026-10-12"],
        },
        "error": None,
        "usage": {"model": "test-model", "promptTokens": 100, "completionTokens": 50},
        "elapsedMs": 300,
    })

    resp = client.post("/agent/v1/diary/review", json={"keyword": "减仓", "limit": 3})

    body = resp.json()
    assert body["mode"] == "llm"
    assert body["theme"] == "持续减仓"
    assert body["hits"][0]["preview"].startswith("继续减仓")
    assert body["citations"] == ["《十月第二周》2026-10-12"]


def test_review_requires_keyword():
    resp = client.post("/agent/v1/diary/review", json={"keyword": "   "})

    assert resp.status_code == 400
    assert resp.json()["code"] == "VALIDATION_ERROR"
