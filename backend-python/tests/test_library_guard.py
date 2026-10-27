"""资料工具与引用约束（P3.3）。

三条要立的规矩：
- 问题里剥不出主题词时**不检索**（拿整句搜，命中全是噪声）；
- 引用只认 Java 按真实片段拼好的 citation，模型自述的出处整段删掉；
- 零命中时不调模型——没素材的总结一定是编的。
"""
from app.agent import library_guard, planner, service


def _hit(title: str, seq: int, snippet: str = "渠道库存周转天数从 42 天降至 35 天") -> dict:
    return {
        "chunkId": seq * 10, "documentId": 3, "title": title, "seq": seq,
        "heading": "渠道跟踪", "snippet": snippet, "sourceType": "PASTE",
        "publisher": "某券商", "publishedAt": "2026-09-20",
        "citation": f"《{title}》· 第 {seq} 段",
    }


def _library_result(hits: list[dict]) -> dict:
    return {
        "tool": "library-search", "ok": True, "error": None, "elapsedMs": 8,
        "data": {"tool": "library-search", "dataAsOf": "2026-09-25T02:00:00Z",
                 "data": {"keyword": "渠道库存", "count": len(hits), "hits": hits},
                 "missing": [] if hits else ["资料库里还没有任何资料"]},
    }


# --------------------------------------------------------------- 关键词


def test_library_question_extracts_keyword():
    planned = dict(planner.plan("我存的资料里关于渠道库存怎么说"))
    assert planned["library-search"]["keyword"] == "渠道库存"


def test_library_question_strips_particles():
    planned = dict(planner.plan("资料库里有没有关于光伏的研报"))
    assert planned["library-search"]["keyword"] == "光伏"


def test_library_question_without_topic_skips_tool():
    # 剥完只剩空话，就不要拿整句去检索：命中会是噪声，比"查不了"更误导
    assert "library-search" not in [name for name, _ in planner.plan("看看我的资料")]


# --------------------------------------------------------------- 引用校验


def test_unknown_title_in_evidence_is_removed_and_replaced_with_real_hits():
    answer = {
        "conclusion": "渠道库存在下降。",
        "evidence": ["《某券商深度报告》· 第 2 段：库存已见底", "库存周转从 42 天降到 35 天"],
        "limits": [],
    }

    cleaned, limits = library_guard.enforce(answer, [_library_result([_hit("白酒渠道调研纪要", 1)])])

    assert cleaned["evidence"] == [
        "库存周转从 42 天降到 35 天",
        "资料实际命中：《白酒渠道调研纪要》· 第 1 段",
    ]
    assert limits and "某券商深度报告" in limits[0]


def test_wrong_section_of_a_real_title_is_also_rejected():
    # 标题对、段号编：这篇只有第 1 段，"第 9 段"同样是编出来的出处
    answer = {
        "conclusion": "渠道库存下降（《白酒渠道调研纪要》· 第 9 段）。",
        "evidence": ["《白酒渠道调研纪要》· 第 1 段：周转天数从 42 天降至 35 天"],
        "limits": [],
    }

    cleaned, limits = library_guard.enforce(answer, [_library_result([_hit("白酒渠道调研纪要", 1)])])

    assert "第 9 段" not in cleaned["conclusion"]
    assert cleaned["conclusion"]  # 不能留下空结论
    assert cleaned["evidence"][0].startswith("《白酒渠道调研纪要》· 第 1 段")
    assert limits


def test_valid_citation_passes_through_untouched():
    answer = {
        "conclusion": "渠道库存在下降（《白酒渠道调研纪要》· 第 1 段）。",
        "evidence": ["《白酒渠道调研纪要》· 第 1 段：周转天数从 42 天降至 35 天"],
        "limits": [],
    }

    cleaned, limits = library_guard.enforce(answer, [_library_result([_hit("白酒渠道调研纪要", 1)])])

    assert cleaned == answer
    assert limits == []


def test_other_tools_are_untouched():
    answer = {"conclusion": "《这是普通书名号》不影响", "evidence": [], "limits": []}
    result = {"tool": "positions", "ok": True, "data": {"data": {"items": []}}}

    cleaned, limits = library_guard.enforce(answer, [result])

    assert cleaned == answer
    assert limits == []


def test_pick_citations_prefers_what_the_answer_actually_cited():
    answer = {"conclusion": "只用了第二段（《纪要》· 第 2 段）", "evidence": [], "limits": []}
    hits = [_hit("纪要", 1), _hit("纪要", 2)]

    picked = library_guard.pick_citations(answer, [_library_result(hits)])

    assert [c["seq"] for c in picked] == [2]
    assert picked[0]["citation"] == "《纪要》· 第 2 段"


def test_pick_citations_falls_back_to_all_hits_when_none_match():
    # 规则模式的依据就是命中片段本身，没有"引用"这个概念：一条都对不上时全部列出
    answer = {"conclusion": "命中 2 段", "evidence": ["渠道库存周转天数下降"], "limits": []}
    hits = [_hit("纪要", 1), _hit("纪要", 2)]

    picked = library_guard.pick_citations(answer, [_library_result(hits)])

    assert [c["seq"] for c in picked] == [1, 2]


# --------------------------------------------------------------- 编排


def test_empty_library_result_skips_model(monkeypatch):
    calls: list[str] = []

    def fake_call(name, params=None):
        calls.append(name)
        return _library_result([])

    def model_must_not_run(*args, **kwargs):
        raise AssertionError("零命中时不应调用模型")

    monkeypatch.setattr(service, "call_tool", fake_call)
    monkeypatch.setattr(service.planner, "plan", lambda q: [("library-search", {"keyword": "渠道库存"})])
    monkeypatch.setattr(service.llm, "is_configured", lambda: True)
    monkeypatch.setattr(service.llm, "analyze", model_must_not_run)

    payload = service.ask("我存的资料里关于渠道库存怎么说")

    assert calls == ["library-search"]
    assert payload["mode"] == "rule_based"
    assert payload["citations"] == []
    # 结论要直说"没有相关资料"，不能只说"没找到符合条件的内容"让人猜哪个环节空了
    assert payload["answer"]["conclusion"].startswith("资料库里没有找到与「渠道库存」相关的资料")
    assert any("没有命中" in note for note in payload["degraded"])


def test_model_answer_is_filtered_before_returning(monkeypatch):
    def fake_call(name, params=None):
        return _library_result([_hit("白酒渠道调研纪要", 1)])

    def fake_model(question, results):
        return {
            "ok": True,
            "answer": {
                "conclusion": "渠道库存在下降。",
                "evidence": ["《不存在的研报》：库存见底"],
                "limits": [],
            },
            "usage": {"model": "fake", "promptTokens": 1, "completionTokens": 1},
            "error": None,
            "elapsedMs": 5,
        }

    monkeypatch.setattr(service, "call_tool", fake_call)
    monkeypatch.setattr(service.planner, "plan", lambda q: [("library-search", {"keyword": "渠道库存"})])
    monkeypatch.setattr(service.llm, "is_configured", lambda: True)
    monkeypatch.setattr(service.llm, "analyze", fake_model)

    payload = service.ask("我存的资料里关于渠道库存怎么说")

    assert payload["mode"] == "llm"
    assert "不存在的研报" not in " ".join(payload["answer"]["evidence"])
    assert any("《白酒渠道调研纪要》" in item for item in payload["answer"]["evidence"])
    assert payload["citations"][0]["documentId"] == 3
    assert any("出处" in limit for limit in payload["answer"]["limits"])
