"""告警解释的编排：模板生成事实 →（可选）模型改写措辞 → 合并返回。

与 ``service.ask`` 的分工一致：模型只负责"说得更像人话"，
**事实、当前状态、数据依据、数据限制永远来自模板**。

两条额外约束：
- 快照缺关键观测值（``snapshotComplete=false``）时不把 why 交给模型——
  这种场景模型极易顺手补一个"看起来合理"的数字，而这正是最不能出现的错误。
- 模型返回的 why/meaning 若为空或结构不对，整体退回模板，不拼接半截结果。
"""
from __future__ import annotations

import time
from datetime import datetime, timezone
from typing import Any

from app.agent import alert_explain, audit, llm


def explain(payload: dict[str, Any]) -> dict[str, Any]:
    started = time.monotonic()
    base = alert_explain.explain(payload)

    degraded: list[str] = []
    usage: dict[str, Any] | None = None
    model_error: str | None = None
    mode = "rule_based"
    why = base["why"]
    meaning = base["meaning"]

    if not base.get("snapshotComplete"):
        degraded.append(
            "事件快照缺少关键观测值，本次解释由模板生成，未经模型改写——"
            "以免模型用事后数据补出一个当时并不存在的数值"
        )
    elif llm.is_configured():
        view = llm.analyze_alert(base)
        if view["ok"]:
            mode = "llm"
            why = view["why"]
            meaning = view["meaning"]
            usage = view["usage"]
        else:
            model_error = view.get("errorCode")
            degraded.append(f"{view['error']}；以下内容由模板生成")
    else:
        degraded.append("未配置模型（STOCKCAT_LLM_* 为空），以下内容由模板生成，不是模型分析结果")

    payload = {
        "eventId": base.get("eventId"),
        "symbol": base.get("symbol"),
        "name": base.get("name"),
        "ruleName": base.get("ruleName"),
        "ruleType": base.get("ruleType"),
        "ruleTypeLabel": base.get("ruleTypeLabel"),
        "condition": base.get("condition"),
        "mode": mode,
        "why": why,
        "meaning": meaning,
        "current": base.get("current"),
        "evidence": base.get("evidence") or [],
        "limits": base.get("limits") or [],
        "snapshotComplete": bool(base.get("snapshotComplete")),
        "generatedAt": _now_iso(),
        "model": usage["model"] if usage else None,
        "usage": usage,
        "degraded": degraded,
        "elapsedMs": int((time.monotonic() - started) * 1000),
    }
    # 告警解释没有工具调用（数据由 Java 组装后传入），tools 恒为 -
    audit.log_run(
        "alert",
        mode=mode,
        model=payload["model"],
        elapsed_ms=payload["elapsedMs"],
        usage=usage,
        error_code=model_error,
        eventId=payload["eventId"],
        ruleType=payload["ruleType"],
        snapshotComplete=payload["snapshotComplete"],
    )
    return payload


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
