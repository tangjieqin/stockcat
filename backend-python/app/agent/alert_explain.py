"""单条告警事件的 AI 解释（P2 场景二）。

输出四段：**为什么触发 / 这意味着什么 / 当前状态 / 数据依据**，另附**数据限制**。

三条硬约束（对应 PLAN_P2 「AI 告警解释」验收标准）：

1. **只读事件快照里真实存在字段**。快照没保存 RSI 数值，就说"没有保存，无法给出
   触发时的具体值"，绝不按当前 K 线回算一遍冒充当时的值——事件的意义正在于它记录的
   是触发那一刻的数据；回算会得出一个看起来合理、实际从未发生过的数字。
2. **语义说明是静态知识**，每类规则一段，只描述"这个指标在讲什么"和"它的局限"，
   不给买卖建议、不给点位、不判断后市。
3. **当前状态单独成段**，与快照严格分开：不能拿今天的指标去解释前天的触发。
   当前数据缺失或过旧时明确说明，不用"暂无法判断"糊过去——要说清是哪种缺。

分工：``why`` / ``meaning`` 允许模型改写措辞（未配置模型则用模板），
``current`` / ``evidence`` / ``limits`` **始终由本模块模板生成**——数据依据不能被模型篡改。
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from typing import Any

CN = timezone(timedelta(hours=8))

_INDICATOR_RULES = {
    "MACD_GOLDEN_CROSS",
    "MACD_DEATH_CROSS",
    "KDJ_GOLDEN_CROSS",
    "KDJ_DEATH_CROSS",
    "RSI_CROSS_ABOVE",
    "RSI_CROSS_BELOW",
}
_PREMIUM_RULES = {"PREMIUM_ABOVE", "PREMIUM_BELOW"}

# 每类规则的静态语义。这里只讲"现象 + 局限"，不写任何操作建议。
_MEANING: dict[str, str] = {
    "PRICE_ABOVE": (
        "你设定的价格阈值被向上触及。这条规则只说明「价格到了某个水平」，"
        "不包含趋势、成交量或消息面的任何判断，单次触及也可能只是瞬时波动。"
    ),
    "PRICE_BELOW": (
        "你设定的价格下限被触及。跌到目标价本身不构成买入理由，"
        "价格继续下行或长期横盘的情况同样常见。"
    ),
    "CHANGE_PERCENT_ABOVE": (
        "当日涨跌幅达到设定幅度。涨跌幅是相对昨收的百分比，不含成交量与消息面信息；"
        "大幅上涨后回落是常见形态。"
    ),
    "CHANGE_PERCENT_BELOW": (
        "当日跌幅达到设定幅度。跌幅是相对昨收的百分比，不含成交量与消息面信息；"
        "单日大跌也可能是除权、停牌复牌等非交易因素造成的。"
    ),
    "MACD_GOLDEN_CROSS": (
        "DIF 上穿 DEA，通常被看作短期动能相对转强的技术信号。"
        "MACD 由均线差值而来，属于滞后指标，在震荡行情中会频繁交叉并产生假信号；"
        "零轴上方的金叉与零轴下方含义并不相同。"
    ),
    "MACD_DEATH_CROSS": (
        "DIF 下穿 DEA，通常被看作短期动能相对转弱的技术信号。"
        "同为滞后指标，死叉在震荡区间里同样会频繁出现，单次死叉不足以确认趋势反转。"
    ),
    "KDJ_GOLDEN_CROSS": (
        "K 上穿 D，通常被看作短期超卖后回升的信号。"
        "KDJ 对短期波动敏感，高位金叉与低位金叉的含义差别很大；"
        "在单边行情中 K、D 会长期钝化，交叉信号失真。"
    ),
    "KDJ_DEATH_CROSS": (
        "K 下穿 D，通常被看作短期走弱的信号。"
        "KDJ 敏感度高，噪声也大；低位死叉常常出现在已经跌了很久之后。"
    ),
    "RSI_CROSS_ABOVE": (
        "RSI 上穿设定阈值，表示近期上涨动能相对增强。"
        "RSI 是区间指标（0~100），在强势行情中可能长期停留在高位，"
        "上穿高位阈值并不等于见顶。"
    ),
    "RSI_CROSS_BELOW": (
        "RSI 下穿设定阈值，表示近期上涨动能减弱。"
        "同理，RSI 在弱势行情中会长期低位钝化，下穿不等于见底。"
    ),
    "PREMIUM_ABOVE": (
        "二级市场价格高于参考净值，此时买入即承担这部分溢价。"
        "溢价随市场情绪快速变化，且参考基准本身可能滞后（用净值作基准时尤其明显），"
        "溢价高不等于价格会回落。"
    ),
    "PREMIUM_BELOW": (
        "二级市场价格低于参考净值（折价）。"
        "折价可能来自流动性不足、市场预期或基准滞后，"
        "折价存在不等于有可执行的套利空间。"
    ),
}

_BASIS_LABEL = {"IOPV": "实时 IOPV", "NAV": "单位净值"}


def explain(payload: dict[str, Any]) -> dict[str, Any]:
    """把一条事件解释成四段。

    ``payload`` 形如 ``{"event": {...}, "current": {...} | None}``，由 Java 组装，
    字段与 ``AgentAlertExplainRequest`` 一致。
    """
    event = payload.get("event") or {}
    current = payload.get("current")
    rule_type = str(event.get("ruleType") or "")
    params = event.get("params") or {}
    detail = event.get("observedDetail") or {}

    limits: list[str] = []
    condition = _condition(rule_type, params, limits)
    why, why_facts, why_missing, snapshot_complete = _why(rule_type, event, detail, params)
    limits.extend(why_missing)

    meaning = _MEANING.get(rule_type, "该规则类型的语义说明尚未内置，请以触发条件与观测值为准。")
    current_text, current_facts, current_missing = _current(rule_type, event, detail, current, params)
    limits.extend(current_missing)

    evidence = list(why_facts) + list(current_facts)
    limits.extend(_data_time_limits(event, current, rule_type))
    if rule_type in _PREMIUM_RULES:
        # Java 这次没取当前溢价率（要另查 IOPV/净值），如实说明，不装作对比过
        limits.append("当前溢价率不在本次取数范围内，未做溢价对比。")
    if not evidence:
        limits.append("这条事件没有任何可引用的观测值，无法说明触发依据。")

    return {
        "eventId": event.get("id"),
        "symbol": event.get("symbol"),
        "name": event.get("name"),
        "ruleName": event.get("ruleName"),
        "ruleType": rule_type,
        "ruleTypeLabel": _label(rule_type),
        "condition": condition,
        "why": why,
        "meaning": meaning,
        "current": current_text,
        "evidence": _dedupe(evidence)[:20],
        "limits": _dedupe(limits)[:20],
        "snapshotComplete": snapshot_complete,
    }


# --------------------------------------------------------------- 为什么触发


def _why(rule_type: str, event: dict[str, Any], detail: dict[str, Any],
         params: dict[str, Any]) -> tuple[str, list[str], list[str], bool]:
    """返回（解释文本、可引用的数据事实、快照缺失项、快照是否足以复述触发依据）。

    最后一个布尔值决定要不要把 why 交给模型改写：快照缺关键观测值时，
    模型很容易"顺手补一个合理数字"，所以这种情况一律走模板。
    """
    facts: list[str] = []
    missing: list[str] = []
    condition = _condition(rule_type, params, missing)
    triggered = _cn_time(event.get("triggeredAt"))

    observed: str | None = None

    if rule_type in ("PRICE_ABOVE", "PRICE_BELOW"):
        price = event.get("observedPrice")
        if price is None:
            missing.append("快照没有记录触发时的价格，无法说明当时的价格水平。")
        else:
            observed = f"触发时最新价 ¥{_num(price)}"
            facts.append(observed)

    elif rule_type in ("CHANGE_PERCENT_ABOVE", "CHANGE_PERCENT_BELOW"):
        pct = event.get("observedChangePercent")
        if pct is None:
            missing.append("快照没有记录触发时的涨跌幅，无法说明当时的涨跌幅度。")
        else:
            observed = f"触发时当日涨跌幅 {_pct(pct)}"
            facts.append(observed)
            if event.get("observedPrice") is not None:
                facts.append(f"触发时最新价 ¥{_num(event['observedPrice'])}")

    elif rule_type in ("MACD_GOLDEN_CROSS", "MACD_DEATH_CROSS"):
        dif, dea = detail.get("dif"), detail.get("dea")
        if dif is None or dea is None:
            missing.append(
                "快照没有保存 DIF/DEA 数值，无法给出触发时的具体指标值"
                "（不回算，避免用事后数据冒充当时值）。"
            )
        else:
            arrow = "上穿" if rule_type == "MACD_GOLDEN_CROSS" else "下穿"
            observed = f"DIF {_ind(dif)} {arrow} DEA {_ind(dea)}"
            facts.append(f"触发时 DIF={_ind(dif)}、DEA={_ind(dea)}")
            hist = detail.get("macdHist")
            if hist is not None:
                facts.append(f"MACD 柱 {_ind(hist, signed=True)}")

    elif rule_type in ("KDJ_GOLDEN_CROSS", "KDJ_DEATH_CROSS"):
        k, d = detail.get("k"), detail.get("d")
        if k is None or d is None:
            missing.append("快照没有保存 K/D 数值，无法给出触发时的具体指标值（不回算）。")
        else:
            arrow = "上穿" if rule_type == "KDJ_GOLDEN_CROSS" else "下穿"
            observed = f"K {_ind(k)} {arrow} D {_ind(d)}"
            facts.append(f"触发时 K={_ind(k)}、D={_ind(d)}")
            j = detail.get("j")
            if j is not None:
                facts.append(f"J 值 {_ind(j)}")

    elif rule_type in ("RSI_CROSS_ABOVE", "RSI_CROSS_BELOW"):
        period = _period(params)
        key = f"rsi{period}"
        value = detail.get(key)
        threshold = params.get("threshold")
        if value is None:
            missing.append(
                f"快照没有保存 RSI{period} 数值，无法给出触发时的具体指标值（不回算）。"
            )
        else:
            arrow = "上穿" if rule_type == "RSI_CROSS_ABOVE" else "下穿"
            target = f" {_num(threshold)}" if threshold is not None else ""
            observed = f"RSI{period} {_ind(value)} {arrow}{target}"
            facts.append(f"触发时 RSI{period}={_ind(value)}")

    elif rule_type in _PREMIUM_RULES:
        premium = detail.get("premium") or {}
        rate = premium.get("premiumRate")
        if rate is None:
            missing.append("快照没有保存溢价率，无法说明触发时的溢价水平。")
        else:
            basis = _BASIS_LABEL.get(str(premium.get("premiumBasis")), "未知基准")
            observed = f"触发时溢价率 {_pct(rate)}（基准：{basis}）"
            facts.append(observed)
            if premium.get("price") is not None:
                facts.append(f"触发时价格 ¥{_num(premium['price'])}")
            if premium.get("iopv") is not None:
                facts.append(f"实时 IOPV {_num(premium['iopv'])}")
            if premium.get("nav") is not None:
                facts.append(f"参考净值 {_num(premium['nav'])}")
            if premium.get("navDate"):
                stale = premium.get("navStaleDays")
                suffix = f"（距今 {stale} 天）" if stale is not None else ""
                facts.append(f"净值日期 {premium['navDate']}{suffix}")

    else:
        price = event.get("observedPrice")
        if price is not None:
            observed = f"触发时最新价 ¥{_num(price)}"
            facts.append(observed)
        missing.append(f"规则类型 {rule_type or '未知'} 的解释模板尚未内置，仅列出原始观测值。")

    head = f"{triggered}，{condition}"
    if observed:
        why = f"{head}：{observed}，条件成立，因此触发。"
    else:
        reason = (missing[0].rstrip("。") + "。") if missing else "快照中没有可引用的观测值。"
        why = f"{head}。事件已触发，但{reason}无法复述当时的具体取值。"
    return why, facts, missing, observed is not None


# ----------------------------------------------------------------- 当前状态


def _current(rule_type: str, event: dict[str, Any], detail: dict[str, Any],
             current: dict[str, Any] | None, params: dict[str, Any]) -> tuple[str, list[str], list[str]]:
    facts: list[str] = []
    missing: list[str] = []
    if not current:
        return ("本次没有取到当前数据，无法与触发时的状态做对比。", facts,
                ["未提供当前行情与指标快照，因此只能解释事件本身。"])

    price = current.get("lastPrice")
    status = current.get("dataStatus")
    market_time = current.get("marketTime") or current.get("collectedAt")

    if price is None:
        parts = ["当前没有可用的行情快照，无法判断现在的状态。"]
        missing.append("当前没有行情快照（可能标的已停牌或尚未采集），当前状态无法判断。")
    else:
        segments = [f"当前最新价 ¥{_num(price)}"]
        if current.get("changePercent") is not None:
            segments.append(f"涨跌幅 {_pct(current['changePercent'])}")
        if market_time:
            segments.append(f"行情时间 {_cn_time(market_time)}")
        if status and status != "VALID":
            segments.append(f"数据状态 {status}（该价格不是最新真实报价，仅供参考）")
            missing.append(f"当前行情状态为 {status}，价格可能已过时，不能当作实时价使用。")
        parts = ["，".join(segments) + "。"]
        facts.append(f"当前最新价 ¥{_num(price)}")

    # 时间倒挂：快照比事件还早，属于"用旧数据解释新事件"，必须点明
    if market_time and event.get("triggeredAt"):
        try:
            market_dt = _parse(market_time)
            triggered_dt = _parse(event["triggeredAt"])
            if market_dt and triggered_dt and market_dt < triggered_dt:
                parts.append("不过该行情时间早于事件触发时间，属于旧数据，不代表触发之后的变化。")
                missing.append("当前行情时间早于事件触发时间，无法反映触发之后的价格变化。")
        except (TypeError, ValueError):
            pass

    if rule_type in _INDICATOR_RULES:
        indicators = current.get("indicators") or {}
        trade_date = current.get("latestTradeDate")
        compare = _indicator_compare(rule_type, detail, indicators, params)
        if compare:
            parts.append(f"最新已收盘日线（{trade_date or '日期未知'}）上，{compare}。")
            facts.append(f"最新指标：{compare}")
            if trade_date and str(detail.get("tradeDate")) == str(trade_date):
                # 收盘后当天不会再有新日线，此时"当前指标"就是触发时那一根，不能让人以为是新信号
                parts.append("这与触发时是同一根 K 线（当日数据尚未更新），不代表触发之后的变化。")
        else:
            bars = current.get("barsAvailable") or 0
            if bars == 0:
                missing.append("当前没有已收盘日 K，无法给出最新指标值。")
                parts.append("当前没有已收盘日 K 数据，最新指标值无法计算。")
            else:
                missing.append("当前指标值缺失或与事件快照不可比，未做数值对比。")

    return "".join(parts), facts, missing


def _indicator_compare(rule_type: str, detail: dict[str, Any],
                       indicators: dict[str, Any], params: dict[str, Any]) -> str | None:
    """把当前指标与触发时对比，只在两边都有值时才给数值。"""
    if not indicators:
        return None
    if rule_type.startswith("MACD"):
        dif, dea = indicators.get("dif"), indicators.get("dea")
        if dif is None or dea is None:
            return None
        text = f"DIF={_ind(dif)}、DEA={_ind(dea)}"
        if detail.get("dif") is not None and detail.get("dea") is not None:
            relation_before = "上方" if detail["dif"] >= detail["dea"] else "下方"
            relation_now = "上方" if dif >= dea else "下方"
            text += f"，DIF 位于 DEA {relation_now}"
            if relation_before != relation_now:
                text += f"（触发时在{relation_before}，已发生反向穿越）"
        return text
    if rule_type.startswith("KDJ"):
        k, d = indicators.get("k"), indicators.get("d")
        if k is None or d is None:
            return None
        return f"K={_ind(k)}、D={_ind(d)}"
    if rule_type.startswith("RSI"):
        period = _period(params)
        value = indicators.get(f"rsi{period}")
        if value is None:
            return None
        text = f"RSI{period}={_ind(value)}"
        threshold = params.get("threshold")
        if threshold is not None:
            side = "上方" if value >= _to_decimal(threshold) else "下方"
            text += f"，位于阈值 {_num(threshold)} {side}"
        return text
    return None


# ------------------------------------------------------------------ 文案工具


def _condition(rule_type: str, params: dict[str, Any], limits: list[str]) -> str:
    threshold = params.get("threshold")
    if rule_type == "PRICE_ABOVE":
        return f"条件是「价格 ≥ ¥{_num(threshold)}」"
    if rule_type == "PRICE_BELOW":
        return f"条件是「价格 ≤ ¥{_num(threshold)}」"
    if rule_type == "CHANGE_PERCENT_ABOVE":
        return f"条件是「涨跌幅 ≥ {_num(threshold)}%」"
    if rule_type == "CHANGE_PERCENT_BELOW":
        return f"条件是「涨跌幅 ≤ {_num(threshold)}%」"
    if rule_type == "MACD_GOLDEN_CROSS":
        return "条件是「DIF 上穿 DEA」（日线，收盘后判定）"
    if rule_type == "MACD_DEATH_CROSS":
        return "条件是「DIF 下穿 DEA」（日线，收盘后判定）"
    if rule_type == "KDJ_GOLDEN_CROSS":
        return "条件是「K 上穿 D」（日线，收盘后判定）"
    if rule_type == "KDJ_DEATH_CROSS":
        return "条件是「K 下穿 D」（日线，收盘后判定）"
    if rule_type == "RSI_CROSS_ABOVE":
        return f"条件是「RSI{_period(params)} 上穿 {_num(threshold)}」（日线，收盘后判定）"
    if rule_type == "RSI_CROSS_BELOW":
        return f"条件是「RSI{_period(params)} 下穿 {_num(threshold)}」（日线，收盘后判定）"
    if rule_type == "PREMIUM_ABOVE":
        return f"条件是「溢价率 ≥ {_num(threshold)}%」"
    if rule_type == "PREMIUM_BELOW":
        return f"条件是「溢价率 ≤ {_num(threshold)}%」"
    limits.append(f"规则类型 {rule_type or '未知'} 没有内置条件文案模板。")
    return f"条件是规则 {rule_type or '未知'}（参数未识别）"


def _label(rule_type: str) -> str:
    return {
        "PRICE_ABOVE": "价格 ≥ 阈值",
        "PRICE_BELOW": "价格 ≤ 阈值",
        "CHANGE_PERCENT_ABOVE": "涨跌幅 ≥ 阈值",
        "CHANGE_PERCENT_BELOW": "涨跌幅 ≤ 阈值",
        "MACD_GOLDEN_CROSS": "MACD 金叉",
        "MACD_DEATH_CROSS": "MACD 死叉",
        "KDJ_GOLDEN_CROSS": "KDJ 金叉",
        "KDJ_DEATH_CROSS": "KDJ 死叉",
        "RSI_CROSS_ABOVE": "RSI 上穿阈值",
        "RSI_CROSS_BELOW": "RSI 下穿阈值",
        "PREMIUM_ABOVE": "溢价率 ≥ 阈值",
        "PREMIUM_BELOW": "溢价率 ≤ 阈值",
    }.get(rule_type, rule_type)


def _data_time_limits(event: dict[str, Any], current: dict[str, Any] | None,
                      rule_type: str) -> list[str]:
    """数据时效说明。

    指标类事件本来就由收盘后日线判定，没有盘中行情时间——写成"无法确认行情新鲜度"
    会让人以为数据丢了一块，其实那是这类事件的正常形态。
    """
    limits: list[str] = []
    detail = event.get("observedDetail") or {}
    if rule_type in _INDICATOR_RULES:
        limits.append(
            "该事件由收盘后日线判定，不含盘中行情时间；指标基于当日收盘数据，收盘后不再变化。"
        )
    elif event.get("quoteMarketTime"):
        limits.append(
            f"判断依据为触发时的行情快照（行情时间 {_cn_time(event['quoteMarketTime'])}），"
            "之后的行情变化不在本解释范围内。"
        )
    else:
        limits.append("事件没有记录行情时间，无法确认触发时行情的新鲜度。")

    trade_date = detail.get("tradeDate")
    if trade_date:
        limits.append(f"指标取值基于 {trade_date} 的收盘日线。")
    if current and current.get("latestTradeDate"):
        latest = current["latestTradeDate"]
        if trade_date and str(latest) == str(trade_date):
            limits.append(f"当前最新日线仍是 {latest}，当日数据尚未更新，无法反映触发之后的变化。")
        else:
            limits.append(f"当前指标基于 {latest} 的收盘日线。")
    return limits


def _period(params: dict[str, Any]) -> int:
    value = params.get("period")
    try:
        return int(value) if value is not None else 6
    except (TypeError, ValueError):
        return 6


def _num(value: Any, signed: bool = False) -> str:
    """价格与阈值按原精度呈现：4.105 不四舍五入成 4.10，差一分就是另一回事。"""
    number = _to_decimal(value)
    if number is None:
        return "—"
    text = f"{number.normalize():f}"
    if signed and number > 0:
        text = "+" + text
    return text


def _ind(value: Any, signed: bool = False) -> str:
    """指标值保留 4 位小数。

    KDJ/MACD 由浮点运算得到，Double 原样输出会变成 ``84.21596397984396``——
    对读者没有任何信息增量，反而淹没重点。4 位够分辨形态，也不至于把差异抹平。
    """
    number = _to_decimal(value)
    if number is None:
        return "—"
    text = f"{number:.4f}".rstrip("0").rstrip(".")
    if text in ("", "-0", "-"):
        text = "0"
    if signed and number > 0:
        text = "+" + text
    return text


def _pct(value: Any) -> str:
    number = _to_decimal(value)
    return "—" if number is None else f"{number:+.2f}%"


def _to_decimal(value: Any) -> Decimal | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None


def _cn_time(raw: Any) -> str:
    parsed = _parse(raw)
    if parsed is None:
        return "时间未知"
    return parsed.astimezone(CN).strftime("%Y-%m-%d %H:%M")


def _parse(raw: Any) -> datetime | None:
    if not raw:
        return None
    try:
        parsed = datetime.fromisoformat(str(raw).replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def _dedupe(items: list[str]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for item in items:
        if item and item not in seen:
            seen.add(item)
            out.append(item)
    return out


def needs_history(rule_type: str) -> bool:
    return rule_type in _INDICATOR_RULES
