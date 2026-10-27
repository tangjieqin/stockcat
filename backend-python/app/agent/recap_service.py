"""每日复盘草稿的编排（P2.3）：取数 → 事实底稿 →（可选）模型成文 → 合并返回。

三条与既有模块一致的分工：
- 模型不在关键路径上：没有 key 也能出一份「只有事实」的草稿（mode=rule_based）；
- ``limitations`` 恒由模板按工具返回生成，模型不能增删——限制项被"润色"掉比编数字更难发现；
- 不落库：本模块只产出内容，落库与人工确认由 Java 侧 ``RecapDraftService`` 负责。
  Python 连不上数据库是 P2 的架构约束，也顺带保证「AI 不能自己发布复盘」。
"""
from __future__ import annotations

import time
from datetime import date as Date, datetime, timedelta, timezone
from typing import Any

from app.agent import audit, llm, recap_draft, service
from app.errors import NO_DATA, ApiError

_ALERT_LIMIT = 8
_PREVIOUS_RECAP_DAYS = 30


def generate(target: Date | None) -> dict[str, Any]:
    """生成一份未落库的复盘草稿。``target`` 为空取今天（Asia/Shanghai）。"""
    started = time.monotonic()
    day = target or _today()
    results = service.run_tools(_plan(day))
    base = recap_draft.compose(day.isoformat(), results)

    if not base["hasMaterial"]:
        # 宁可报错也不写一篇"今天市场平淡"的空话：那是最难被发现的编造
        audit.log_run(
            "recap",
            mode=None,
            elapsed_ms=int((time.monotonic() - started) * 1000),
            tool_calls=[service.tool_call_view(result) for result in results],
            error_code=NO_DATA,
            error="没有取到任何指数、自选或持仓数据",
            date=day.isoformat(),
        )
        raise ApiError(
            502,
            NO_DATA,
            "没有取到任何指数、自选或持仓数据，无法生成复盘草稿；"
            "请确认 backend-java 已启动并完成过一次行情采集",
        )

    mode = "rule_based"
    draft = base["draft"]
    usage: dict[str, Any] | None = None
    degraded: list[str] = []
    model_error: str | None = None

    if llm.is_configured():
        view = llm.draft_recap(recap_draft.llm_prompt(day.isoformat(), results, base["limitations"]))
        if view["ok"]:
            mode = "llm"
            draft = view["draft"]
            usage = view["usage"]
        else:
            model_error = view.get("errorCode")
            degraded.append(f"{view['error']}；以下内容由规则底稿生成")
    else:
        degraded.append("未配置模型（STOCKCAT_LLM_* 为空），以下内容由规则底稿生成，不是模型撰写")

    payload = {
        "date": day.isoformat(),
        "mode": mode,
        "draft": draft,
        # limitations 只取模板结果，模型输出里的限制项一概不采信
        "limitations": base["limitations"],
        "generatedAt": _now_iso(),
        "dataAsOf": service.latest_data_as_of(results),
        "toolCalls": [service.tool_call_view(result) for result in results],
        "model": usage["model"] if usage else None,
        "usage": usage,
        "degraded": degraded,
        "elapsedMs": int((time.monotonic() - started) * 1000),
    }
    audit.log_run(
        "recap",
        mode=mode,
        model=payload["model"],
        elapsed_ms=payload["elapsedMs"],
        tool_calls=payload["toolCalls"],
        usage=usage,
        error_code=model_error,
        date=payload["date"],
    )
    return payload


def _plan(day: Date) -> list[tuple[str, dict[str, Any]]]:
    """固定取 5 个工具，不做关键词规划：写复盘需要的上下文是确定的，不需要猜。"""
    return [
        ("market-brief", {"date": day.isoformat()}),
        ("positions", {}),
        ("watchlist-quotes", {}),
        ("alert-events", {"limit": _ALERT_LIMIT}),
        ("recaps", {
            "from": (day - timedelta(days=_PREVIOUS_RECAP_DAYS)).isoformat(),
            "to": (day - timedelta(days=1)).isoformat(),
            "limit": 3,
        }),
    ]


def _today() -> Date:
    return datetime.now(timezone(timedelta(hours=8))).date()


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
