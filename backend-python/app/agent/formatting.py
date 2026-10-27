"""数值与时间格式化：Agent 各模块共用一份。

为什么单独拎出来：同一个数字在"问答"和"复盘草稿"里必须写成同一个样子。
两处各写一份格式化，迟早出现"持仓页 5.34%、复盘里 5.3%"这种同源不同值的差异。

三条约定：
- 价格/成本按原精度显示（4.105 不能变成 4.10，成本价差一分就是另一回事）；
- 金额千分位、两位小数；涨跌幅恒带正负号，看符号就知道方向；
- 拿不到值一律返回 None，由调用方决定写"—"还是写"数据缺失"，**不补 0**。
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from typing import Any

CN = timezone(timedelta(hours=8))


def money(value: Any) -> str | None:
    number = decimal_or_none(value)
    return None if number is None else f"{number:,.2f}"


def signed(value: Any) -> str | None:
    number = decimal_or_none(value)
    return None if number is None else f"{number:+,.2f}"


def pct(value: Any) -> str | None:
    number = decimal_or_none(value)
    return None if number is None else f"{number:+.2f}%"


def qty(value: Any) -> str:
    number = decimal_or_none(value)
    if number is None:
        return "—"
    return f"{number.normalize():f}"


def price(value: Any) -> str | None:
    """价格/成本按原精度显示：不做四舍五入。"""
    number = decimal_or_none(value)
    return None if number is None else f"{number.normalize():f}"


def decimal_or_none(value: Any) -> Decimal | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None


def cn_time(raw: Any) -> str:
    if not raw:
        return "时间未知"
    try:
        parsed = datetime.fromisoformat(str(raw).replace("Z", "+00:00"))
    except ValueError:
        return str(raw)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(CN).strftime("%m-%d %H:%M")
