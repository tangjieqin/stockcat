"""AI 运行日志（P2.6）：记什么、怎么记、以及绝对不能记什么。"""
import logging

import pytest

from app.agent import audit, recap_service, service
from app.errors import ApiError


def _lines(caplog) -> list[str]:
    return [r.getMessage() for r in caplog.records if r.name == "stockcat.agent"]


def test_success_line_has_scene_mode_tools_usage(caplog):
    with caplog.at_level(logging.INFO, logger="stockcat.agent"):
        audit.log_run(
            "assistant",
            mode="llm",
            model="gpt-4o-mini",
            elapsed_ms=812,
            tool_calls=[{"tool": "positions", "ok": True}, {"tool": "recaps", "ok": False}],
            usage={"promptTokens": 1234, "completionTokens": 312},
            qChars=12,
        )

    line = _lines(caplog)[-1]
    assert line.startswith("agent scene=assistant mode=llm qChars=12 model=gpt-4o-mini")
    assert "elapsedMs=812" in line
    assert "tools=positions:ok,recaps:fail" in line
    assert "usage=1234+312" in line
    assert line.endswith("err=-")


def test_rule_based_without_model_logs_dash_usage(caplog):
    with caplog.at_level(logging.INFO, logger="stockcat.agent"):
        audit.log_run("alert", mode="rule_based", elapsed_ms=3, eventId=42, snapshotComplete=False)

    line = _lines(caplog)[-1]
    assert "mode=rule_based" in line
    assert "model=-" in line
    assert "tools=-" in line
    assert "usage=-" in line
    assert "eventId=42 snapshotComplete=False" in line


def test_error_text_is_flattened_and_truncated(caplog):
    """上游错误可能带 HTML 或大段堆栈：压成一行并截断，不然一条日志能刷满屏幕。"""
    noisy = "模型服务返回 HTTP 502\n<html>" + "x" * 500

    with caplog.at_level(logging.INFO, logger="stockcat.agent"):
        audit.log_run("recap", mode="rule_based", error_code="MODEL_ERROR", error=noisy, date="2026-10-27")

    line = _lines(caplog)[-1]
    assert "\n" not in line
    assert len(line) < 400
    assert "err=MODEL_ERROR" in line
    # 截断到 120 字符：500 个 x 不该原样进日志
    assert line.count("x") <= 120


def test_never_logs_prompt_or_key(caplog):
    """签名里根本没有 prompt / answer / key 的位置，这条测试锁住"新增字段别加错"。"""
    with caplog.at_level(logging.INFO, logger="stockcat.agent"):
        audit.log_run("assistant", mode="llm", model="gpt-4o-mini", elapsed_ms=1, qChars=7)

    line = _lines(caplog)[-1]
    assert "sk-" not in line
    assert "Bearer" not in line


def test_ask_logs_scene_with_tool_outcomes(caplog, monkeypatch):
    def fake_call(name, params=None):
        return {"tool": name, "ok": True, "error": None, "elapsedMs": 2,
                "data": {"tool": name, "dataAsOf": None, "data": {"count": 0, "items": []}, "missing": []}}

    monkeypatch.setattr(service, "call_tool", fake_call)
    monkeypatch.setattr(service.planner, "plan", lambda question: [("market-brief", {})])
    monkeypatch.setattr(service.llm, "is_configured", lambda: False)

    with caplog.at_level(logging.INFO, logger="stockcat.agent"):
        service.ask("今天大盘怎么样")

    line = _lines(caplog)[-1]
    assert "scene=assistant" in line
    assert "mode=rule_based" in line
    assert "tools=market-brief:ok" in line
    assert "qChars=7" in line


def test_recap_no_data_logs_error_code(caplog, monkeypatch):
    """拒绝生成也要留痕：否则用户只说"点了没反应"，日志里什么都没有。"""
    monkeypatch.setattr(recap_service.service, "run_tools", lambda planned: [])
    monkeypatch.setattr(recap_service.llm, "is_configured", lambda: False)

    with caplog.at_level(logging.INFO, logger="stockcat.agent"):
        with pytest.raises(ApiError) as exc:
            recap_service.generate(None)

    assert exc.value.code == "NO_DATA"
    line = _lines(caplog)[-1]
    assert "scene=recap" in line
    assert "err=NO_DATA" in line
    assert "errText=没有取到任何指数、自选或持仓数据" in line
