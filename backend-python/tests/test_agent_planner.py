"""问题 → 工具的规则：命中哪些关键词就查哪些数据，绝不凭空多查。"""
from app.agent import planner


def _tools(question: str) -> list[str]:
    return [name for name, _ in planner.plan(question)]


def test_position_question_queries_positions():
    assert "positions" in _tools("我的持仓现在怎么样")


def test_alert_question_queries_alert_events():
    assert _tools("最近有哪些告警") == ["alert-events"]


def test_recap_question_queries_recap_and_brief():
    tools = _tools("帮我生成今天的市场复盘")
    assert tools == ["recaps", "market-brief"]


def test_unknown_question_falls_back_to_default_set():
    assert _tools("你好") == ["positions", "watchlist-quotes", "alert-events"]


def test_diary_question_extracts_keyword():
    planned = dict(planner.plan("我日记里关于白酒的记录"))
    assert planned["diary-search"]["keyword"] == "白酒"


def test_diary_question_without_keyword_skips_diary_tool():
    # 剥完只剩"日记"本身，没有可用关键词，就不要拿整句去搜
    assert "diary-search" not in _tools("看看我的日记")


def test_tool_list_is_capped():
    # 一句话同时命中持仓/行情/告警/复盘，也不能无限取数
    tools = _tools("结合持仓、行情、告警和复盘分析一下")
    assert len(tools) <= 4


def test_params_carry_limits():
    planned = dict(planner.plan("最近有什么告警"))
    assert planned["alert-events"]["limit"] == 5
