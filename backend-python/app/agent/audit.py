"""一次 AI 调用的运行日志（P2.6）。

只记"哪个场景、用了哪个模型、调了哪些工具、耗时多久、多少 token、失败码是什么"。

三样东西**永远不写进日志**：
- API Key（这里只记模型名，配置项本身不进日志）；
- prompt 原文（含用户问题与持仓明细）；
- 回答正文（同上）。

格式固定为单行 ``key=value``，方便 grep 与按 scene 统计：

    agent scene=recap date=2026-10-27 mode=llm model=gpt-4o-mini elapsedMs=8123
          tools=market-brief:ok,positions:ok usage=1234+312 err=-

失败路径也要写一条（``mode`` 为空、带 ``err``），否则"用户说 AI 不好用"时无从查起。
"""
from __future__ import annotations

import logging
from typing import Any

logger = logging.getLogger("stockcat.agent")

# 错误文案可能带上游 HTML 或超长 URL，截断并压成一行，避免日志被一条记录撑爆
_MAX_TEXT_CHARS = 120


def log_run(
    scene: str,
    *,
    mode: str | None = None,
    model: str | None = None,
    elapsed_ms: int | None = None,
    tool_calls: list[dict[str, Any]] | None = None,
    usage: dict[str, Any] | None = None,
    error_code: str | None = None,
    error: str | None = None,
    **extra: Any,
) -> None:
    """写一条运行日志。``extra`` 放场景相关的非敏感字段（date / eventId / qChars）。"""
    parts = [f"scene={scene}", f"mode={_text(mode)}"]
    for key, value in extra.items():
        parts.append(f"{key}={_text(value)}")
    parts.append(f"model={_text(model)}")
    parts.append(f"elapsedMs={'-' if elapsed_ms is None else elapsed_ms}")
    parts.append(f"tools={_tools(tool_calls)}")
    parts.append(f"usage={_usage(usage)}")
    parts.append(f"err={_text(error_code)}")
    if error:
        parts.append(f"errText={_text(error)}")
    logger.info("agent %s", " ".join(parts))


def _tools(tool_calls: list[dict[str, Any]] | None) -> str:
    if not tool_calls:
        return "-"
    return ",".join(
        f"{item.get('tool') or '?'}:{'ok' if item.get('ok') else 'fail'}" for item in tool_calls
    )


def _usage(usage: dict[str, Any] | None) -> str:
    if not usage:
        return "-"
    prompt = usage.get("promptTokens")
    completion = usage.get("completionTokens")
    if prompt is None and completion is None:
        return "-"
    return f"{_num(prompt)}+{_num(completion)}"


def _num(value: Any) -> str:
    return "-" if value is None else str(value)


def _text(value: Any) -> str:
    """非敏感单值：空值统一写 ``-``，并压掉换行。"""
    if value is None or value == "":
        return "-"
    text = " ".join(str(value).split())
    return text[:_MAX_TEXT_CHARS] if text else "-"
