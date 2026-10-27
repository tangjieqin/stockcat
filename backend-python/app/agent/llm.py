"""可选的大模型分析层（OpenAI 兼容协议）。

三个环境变量决定是否启用：``STOCKCAT_LLM_BASE_URL`` / ``STOCKCAT_LLM_MODEL`` / ``STOCKCAT_LLM_API_KEY``。
任一缺失即视为未配置，服务退回规则汇总——**AI 不在关键路径上**，没有 key 页面照样能用。

三条硬约束（既写在 prompt 里，也写在这里的实现里）：
1. 数值只能来自工具返回，缺数据就写"数据缺失"，禁止推测或补 0；
2. 不给买卖建议、不预测点位，只描述事实与变化；
3. 只接受 JSON，解析失败即失败（返回 None 让上层降级），不去猜模型想说什么。

日志只记模型名/耗时/token；不记 key，也不记完整 prompt。
"""
from __future__ import annotations

import json
import time
from typing import Any

import httpx

from app.config import settings

MODEL_NOT_CONFIGURED = "MODEL_NOT_CONFIGURED"
MODEL_TIMEOUT = "MODEL_TIMEOUT"
MODEL_ERROR = "MODEL_ERROR"
MODEL_OUTPUT_INVALID = "MODEL_OUTPUT_INVALID"

_MAX_ITEMS = 8
_MAX_OUTPUT_TOKENS = 900
_MAX_ALERT_TOKENS = 500
_MAX_RECAP_TOKENS = 1800
_MAX_DIARY_TOKENS = 1500
# market_recap.title 是 VARCHAR(128)：模型偶尔写长标题，落库前必须截断
_MAX_TITLE_CHARS = 128

_SYSTEM_PROMPT = """你是 StockCat 的投资复盘助手，只能依据用户请求中提供的业务数据作答。

规则（违反即视为错误输出）：
1. 所有数值必须来自给定的业务数据块，禁止推测、禁止估算、禁止把缺失值当 0。
2. 数据里没有的东西，就在 limits 里写明"缺少什么"，不要绕过去编一个。
3. 不提供买卖建议、目标价、点位预测；只描述已经发生的事实与变化。
4. 涉及时间时必须说明数据截至时间，不能让用户以为是最新实时值。
5. 引用资料库（library-search）的内容时，出处必须**原样照抄**该条命中的 citation 字段
   （形如《标题》· 第 3 段）；不要自己拼标题、日期或段号，也不要用命中列表以外的出处。
   没有命中资料就直说"资料库里没有找到"，不得用你自己的知识补充"相关背景"。

只输出一个 JSON 对象，不要 markdown 代码块、不要额外解释，结构如下：
{"conclusion": "两三句话的分析结论", "evidence": ["引用的具体数据，一条一个事实"], "limits": ["本次回答的数据限制"]}
"""

_ALERT_SYSTEM_PROMPT = """你是 StockCat 的告警解释助手。用户会给出一条已触发告警事件的
事实底稿，其中 why/meaning 是系统按规则类型生成的基础文案，其余字段是事件快照里的真实数据。

你的任务：改写 why 和 meaning 两段，让它们更连贯、更适合普通投资者阅读，但事实不许变。

硬性禁止（违反即视为错误输出）：
1. 不得引入底稿以外的任何数值、日期、指标或事件；
2. 不得把底稿里"未保存/无法给出"的指标补成具体数字；
3. 不得给买卖建议、目标价、仓位建议、后市预测；
4. 不得编造涨跌原因（政策、消息、资金流等底稿里没有的因果）；
5. 不得改变触发条件的比较方向（≥ 不能写成 >，上穿不能写成下穿）。

只输出一个 JSON 对象，不要 markdown 代码块：
{"why": "为什么触发，一到三句，含关键数值", "meaning": "这意味着什么，含该指标的局限"}
"""


_RECAP_SYSTEM_PROMPT = """你是 StockCat 的每日复盘起草助手。用户会给出复盘归属日期、当天可得的业务数据，
以及一组成员已知的数据限制。请据此写一份**复盘草稿**，供用户编辑确认。

硬性禁止（违反即视为错误输出）：
1. 不得出现数据块以外的任何数值、日期、点位、涨跌幅、成本、市值；不得自己换算（如用份额×价格算市值）。
2. 数据块里缺失或调用失败的部分，就写"数据缺失"，绝不补 0，也不用"约""大概"补一个数。
3. 不得写买卖建议、目标价、仓位建议、后市预测、点位判断；只描述已经发生的事实与变化。
4. 不得编造涨跌原因（政策、消息、资金流、北向资金等数据块里没有的因果）。
5. 不得把采集时点快照写成"收盘价""收盘点位"。
6. 本次没有采集行业板块数据：板块表现只能写自选分组汇总，并保留"非行业板块"的限定。
7. 已知数据限制必须原样出现在 content 的「数据说明」小节，不能删改、不能弱化。

只输出一个 JSON 对象，不要 markdown 代码块、不要额外解释，结构如下：
{"title": "不超过 32 字的标题", "marketSummary": "2~4 句，含指数与自选涨跌的关键数值", "sectorNotes": "自选分组与持仓结构的变化，注明非行业板块数据", "content": "Markdown 正文，含 大盘/自选表现/持仓/告警/前情/数据说明 六个小节"}
"""


_DIARY_REFINE_PROMPT = """你是 StockCat 的日记整理助手。用户会给出自己写的一篇投资日记（可能很散乱），
以及一份已知标的白名单。

你的任务是整理，不是改写观点：把内容归类清楚，但不替用户判断对错、不补充他没写过的事实。

硬性禁止（违反即视为错误输出）：
1. 不得引入原文没有的数字、日期、标的、盈亏、仓位；不得自己换算（如用价格×份额算市值）。
2. instruments 只能从给的白名单里选，白名单外的一律不写——哪怕原文里明明白白出现了那个代码。
3. 不得写投资建议、目标价、点位预测；整理的始终是用户自己的想法。
4. refined 必须是原文信息的重组：可以分小节、加小标题，但不得新增内容，
   也不得把"待跟进"这类事项删掉——那正是用户回头要找的东西。
5. summary 一句话，不超过 60 字，只概括原文写了什么。

只输出一个 JSON 对象，不要 markdown 代码块：
{"summary": "...", "highlights": ["..."], "instruments": [{"symbol": "...", "name": "..."}], "followUps": ["..."], "refined": "Markdown 正文"}
"""

_DIARY_REVIEW_PROMPT = """你是 StockCat 的日记回顾助手。用户给出一个关键词，以及按该关键词检索命中的
若干篇日记片段。

你的任务是做回顾，不是评价：指出这些日记里反复出现的主题、一直没解决的事、以及明显的空白。

硬性禁止（违反即视为错误输出）：
1. 只能引用命中列表里的日记，title 必须与列表完全一致；不得虚构篇目、日期或原文没有的观点。
2. 不得引入行情、盈亏、政策等命中片段之外的事实。
3. 不得给买卖建议、不得预测后市、不得评价用户水平。
4. 命中片段很短时如实说"片段有限"，不要用推测补全。

只输出一个 JSON 对象，不要 markdown 代码块：
{"theme": "这次回顾的主题，一到两句", "threads": [{"title": "命中日记的完整标题", "point": "这篇里的关键点"}], "recurring": ["反复出现的事"], "gaps": ["日记里没写清楚的地方"], "citations": ["《标题》日期"]}
"""


def is_configured() -> bool:
    return settings.llm_configured


def analyze(question: str, results: list[dict[str, Any]]) -> dict[str, Any]:
    """调用模型生成分析结论。

    返回 ``{"ok", "answer", "error", "usage", "elapsedMs"}``；``answer`` 形如
    ``{"conclusion": str, "evidence": [str], "limits": [str]}``。
    """
    if not is_configured():
        return _fail(MODEL_NOT_CONFIGURED, "未配置模型（STOCKCAT_LLM_* 为空），本次为规则汇总")

    result = _chat(_SYSTEM_PROMPT, _user_prompt(question, results), _MAX_OUTPUT_TOKENS)
    if not result["ok"]:
        return _fail(result["errorCode"], result["error"], elapsed=result["elapsedMs"])

    answer = _parse_answer(result["content"])
    if answer is None:
        return _fail(MODEL_OUTPUT_INVALID, "模型输出不是可解析的 JSON，已降级为规则汇总",
                     elapsed=result["elapsedMs"])
    return {
        "ok": True,
        "answer": answer,
        "error": None,
        "usage": result["usage"],
        "elapsedMs": result["elapsedMs"],
    }


def analyze_alert(base: dict[str, Any]) -> dict[str, Any]:
    """告警解释的措辞改写。

    只允许模型改写 ``why`` / ``meaning`` 两段；``current`` / ``evidence`` / ``limits``
    由模板生成，不经过模型——数据依据不能被模型"润色"。

    返回 ``{"ok", "why", "meaning", "error", "usage", "elapsedMs"}``。
    """
    if not is_configured():
        return _fail_alert(MODEL_NOT_CONFIGURED, "未配置模型（STOCKCAT_LLM_* 为空），本次为模板生成")

    result = _chat(_ALERT_SYSTEM_PROMPT, _alert_user_prompt(base), _MAX_ALERT_TOKENS)
    if not result["ok"]:
        return _fail_alert(result["errorCode"], result["error"], elapsed=result["elapsedMs"])

    parsed = _parse_alert_answer(result["content"])
    if parsed is None:
        return _fail_alert(MODEL_OUTPUT_INVALID, "模型输出不是可解析的 JSON，已降级为模板生成",
                           elapsed=result["elapsedMs"])
    return {
        "ok": True,
        "why": parsed["why"],
        "meaning": parsed["meaning"],
        "error": None,
        "usage": result["usage"],
        "elapsedMs": result["elapsedMs"],
    }


def draft_recap(user_prompt: str) -> dict[str, Any]:
    """生成复盘草稿。

    返回 ``{"ok", "draft", "error", "usage", "elapsedMs"}``；``draft`` 形如
    ``{"title", "marketSummary", "sectorNotes", "content"}``。

    解析失败不重试、不猜：返回 ``MODEL_OUTPUT_INVALID``，由上层退回规则底稿。
    """
    if not is_configured():
        return _fail_recap(MODEL_NOT_CONFIGURED, "未配置模型（STOCKCAT_LLM_* 为空），本次为规则底稿")

    result = _chat(_RECAP_SYSTEM_PROMPT, user_prompt, _MAX_RECAP_TOKENS)
    if not result["ok"]:
        return _fail_recap(result["errorCode"], result["error"], elapsed=result["elapsedMs"])

    draft = _parse_recap(result["content"])
    if draft is None:
        return _fail_recap(MODEL_OUTPUT_INVALID, "模型输出不是可解析的复盘 JSON，已降级为规则底稿",
                           elapsed=result["elapsedMs"])
    return {
        "ok": True,
        "draft": draft,
        "error": None,
        "usage": result["usage"],
        "elapsedMs": result["elapsedMs"],
    }


def refine_diary(user_prompt: str) -> dict[str, Any]:
    """整理日记。返回 ``{"ok", "view", "error", "usage", "elapsedMs"}``。

    ``view`` 是模型原始 JSON，业务校验（标的白名单、段落是否齐全）由
    ``diary_assist.accept_refine`` 做——模型层只负责"拿到一个 JSON"。"""
    if not is_configured():
        return _fail_diary(MODEL_NOT_CONFIGURED, "未配置模型（STOCKCAT_LLM_* 为空），本次为规则提取")

    result = _chat(_DIARY_REFINE_PROMPT, user_prompt, _MAX_DIARY_TOKENS)
    if not result["ok"]:
        return _fail_diary(result["errorCode"], result["error"], elapsed=result["elapsedMs"])

    view = _parse_object(result["content"])
    if view is None:
        return _fail_diary(MODEL_OUTPUT_INVALID, "模型输出不是可解析的 JSON，已降级为规则提取",
                           elapsed=result["elapsedMs"])
    return {
        "ok": True,
        "view": view,
        "error": None,
        "usage": result["usage"],
        "elapsedMs": result["elapsedMs"],
    }


def review_diary(user_prompt: str) -> dict[str, Any]:
    """按关键词回顾日记。返回结构与 :func:`refine_diary` 相同。"""
    if not is_configured():
        return _fail_diary(MODEL_NOT_CONFIGURED, "未配置模型（STOCKCAT_LLM_* 为空），本次只做检索")

    result = _chat(_DIARY_REVIEW_PROMPT, user_prompt, _MAX_DIARY_TOKENS)
    if not result["ok"]:
        return _fail_diary(result["errorCode"], result["error"], elapsed=result["elapsedMs"])

    view = _parse_object(result["content"])
    if view is None:
        return _fail_diary(MODEL_OUTPUT_INVALID, "模型输出不是可解析的 JSON，已降级为检索罗列",
                           elapsed=result["elapsedMs"])
    return {
        "ok": True,
        "view": view,
        "error": None,
        "usage": result["usage"],
        "elapsedMs": result["elapsedMs"],
    }


def _chat(system_prompt: str, user_prompt: str, max_tokens: int) -> dict[str, Any]:
    """OpenAI 兼容协议的单次调用。三种用途共用，避免多份 HTTP 代码各自漂移。

    返回 ``{"ok", "content", "usage", "errorCode", "error", "elapsedMs"}``。
    """
    payload = {
        "model": settings.llm_model,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        "temperature": 0.2,
        "max_tokens": max_tokens,
    }
    started = time.monotonic()
    try:
        with httpx.Client(timeout=settings.llm_timeout_seconds) as client:
            resp = client.post(
                settings.llm_base_url.rstrip("/") + "/chat/completions",
                headers={"Authorization": f"Bearer {settings.llm_api_key}"},
                json=payload,
            )
    except httpx.TimeoutException:
        return _chat_fail(MODEL_TIMEOUT, f"模型调用超时（{settings.llm_timeout_seconds:.0f}s）", started)
    except httpx.HTTPError as exc:
        return _chat_fail(MODEL_ERROR, f"模型服务不可用：{exc}", started)

    elapsed = _elapsed_ms(started)
    if resp.status_code != 200:
        return _chat_fail(MODEL_ERROR, f"模型服务返回 HTTP {resp.status_code}", started, elapsed)

    try:
        body = resp.json()
        content = body["choices"][0]["message"]["content"]
        usage = body.get("usage") or {}
    except (ValueError, KeyError, IndexError, TypeError):
        return _chat_fail(MODEL_ERROR, "模型响应结构不符合 OpenAI 兼容格式", started, elapsed)

    return {
        "ok": True,
        "content": content,
        "usage": {
            "model": settings.llm_model,
            "promptTokens": usage.get("prompt_tokens"),
            "completionTokens": usage.get("completion_tokens"),
        },
        "errorCode": None,
        "error": None,
        "elapsedMs": elapsed,
    }


def _chat_fail(code: str, detail: str, started: float, elapsed: int | None = None) -> dict[str, Any]:
    return {
        "ok": False,
        "content": None,
        "usage": None,
        "errorCode": code,
        "error": detail,
        "elapsedMs": elapsed if elapsed is not None else _elapsed_ms(started),
    }


def _user_prompt(question: str, results: list[dict[str, Any]]) -> str:
    blocks = []
    for result in results:
        if not result.get("ok"):
            blocks.append(f"[{result['tool']}] 调用失败：{result.get('error')}")
            continue
        body = result.get("data") or {}
        blocks.append(
            f"[{result['tool']}] dataAsOf={body.get('dataAsOf')}\n"
            f"缺失项={json.dumps(body.get('missing') or [], ensure_ascii=False)}\n"
            f"数据={json.dumps(_compact(body.get('data')), ensure_ascii=False)}"
        )
    return ("用户问题：" + question + "\n\n可用业务数据（唯一事实来源）：\n" + "\n\n".join(blocks))


def _alert_user_prompt(base: dict[str, Any]) -> str:
    """把模板底稿原样交给模型：模型只能改写 why/meaning，其余字段是对照用的锚点。"""
    block = {
        "condition": base.get("condition"),
        "why": base.get("why"),
        "meaning": base.get("meaning"),
        "current": base.get("current"),
        "evidence": base.get("evidence"),
        "limits": base.get("limits"),
        "event": {
            "symbol": base.get("symbol"),
            "name": base.get("name"),
            "ruleType": base.get("ruleType"),
            "ruleTypeLabel": base.get("ruleTypeLabel"),
        },
    }
    return (
        "以下是告警事件的事实底稿（唯一事实来源，数值不得改动、不得新增）：\n"
        + json.dumps(block, ensure_ascii=False, indent=2)
        + "\n\n请按要求改写 why 与 meaning 两段，只输出 JSON。"
    )


def _compact(data: Any) -> Any:
    """列表只留前 N 条：够写结论即可，别把整库塞进 prompt。"""
    if isinstance(data, dict):
        out: dict[str, Any] = {}
        for key, value in data.items():
            if isinstance(value, list):
                out[key] = value[:_MAX_ITEMS]
            else:
                out[key] = _compact(value) if isinstance(value, dict) else value
        return out
    return data


def _parse_answer(content: str | None) -> dict[str, Any] | None:
    if not content or not content.strip():
        return None
    text = content.strip()
    if text.startswith("```"):
        # 少数模型无视"不要代码块"，剥掉围栏再解析
        text = text.strip("`")
        text = text.split("\n", 1)[1] if "\n" in text else text
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        parsed = json.loads(text[start:end + 1])
    except ValueError:
        return None
    conclusion = parsed.get("conclusion")
    if not isinstance(conclusion, str) or not conclusion.strip():
        return None
    return {
        "conclusion": conclusion.strip(),
        "evidence": _string_list(parsed.get("evidence")),
        "limits": _string_list(parsed.get("limits")),
    }


def _string_list(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item).strip() for item in value if str(item).strip()][:10]


def _parse_alert_answer(content: str | None) -> dict[str, str] | None:
    """告警解释只要两段文本；缺任一段即视为不可用，不拿半截结果凑。"""
    if not content or not content.strip():
        return None
    text = content.strip()
    if text.startswith("```"):
        text = text.strip("`")
        text = text.split("\n", 1)[1] if "\n" in text else text
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        parsed = json.loads(text[start:end + 1])
    except ValueError:
        return None
    why = parsed.get("why")
    meaning = parsed.get("meaning")
    if not isinstance(why, str) or not why.strip():
        return None
    if not isinstance(meaning, str) or not meaning.strip():
        return None
    return {"why": why.strip()[:1200], "meaning": meaning.strip()[:1200]}


def _parse_recap(content: str | None) -> dict[str, str] | None:
    """四段都必须非空：缺任何一段就整篇退回规则底稿，不拿半截草稿凑数。"""
    if not content or not content.strip():
        return None
    text = content.strip()
    if text.startswith("```"):
        text = text.strip("`")
        text = text.split("\n", 1)[1] if "\n" in text else text
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        parsed = json.loads(text[start:end + 1])
    except ValueError:
        return None
    if not isinstance(parsed, dict):
        return None
    draft: dict[str, str] = {}
    for key in ("title", "marketSummary", "sectorNotes", "content"):
        value = parsed.get(key)
        if not isinstance(value, str) or not value.strip():
            return None
        draft[key] = value.strip()
    draft["title"] = draft["title"][:_MAX_TITLE_CHARS]
    draft["marketSummary"] = draft["marketSummary"][:2000]
    draft["sectorNotes"] = draft["sectorNotes"][:2000]
    draft["content"] = draft["content"][:8000]
    return draft


def _parse_object(content: str | None) -> dict[str, Any] | None:
    """从模型输出里抠一个 JSON 对象出来；抠不到就返回 None，让上层降级。

    不重试、不猜——"再问一次也许就对了"会让同一次请求的耗时翻倍且结果不可复现。
    """
    if not content or not content.strip():
        return None
    text = content.strip()
    if text.startswith("```"):
        text = text.strip("`")
        text = text.split("\n", 1)[1] if "\n" in text else text
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        parsed = json.loads(text[start:end + 1])
    except ValueError:
        return None
    return parsed if isinstance(parsed, dict) else None


def _fail_diary(code: str, detail: str, started: float | None = None,
                elapsed: int | None = None) -> dict[str, Any]:
    return {
        "ok": False,
        "view": None,
        "error": f"{code}: {detail}",
        "usage": None,
        "elapsedMs": elapsed if elapsed is not None else _elapsed_ms(started or time.monotonic()),
    }


def _fail_recap(code: str, detail: str, started: float | None = None,
                elapsed: int | None = None) -> dict[str, Any]:
    return {
        "ok": False,
        "draft": None,
        "error": f"{code}: {detail}",
        "usage": None,
        "elapsedMs": elapsed if elapsed is not None else _elapsed_ms(started or time.monotonic()),
    }


def _fail_alert(code: str, detail: str, started: float | None = None,
                elapsed: int | None = None) -> dict[str, Any]:
    return {
        "ok": False,
        "why": None,
        "meaning": None,
        "error": f"{code}: {detail}",
        "usage": None,
        "elapsedMs": elapsed if elapsed is not None else _elapsed_ms(started or time.monotonic()),
    }


def _fail(code: str, detail: str, started: float | None = None, elapsed: int | None = None) -> dict[str, Any]:
    return {
        "ok": False,
        "answer": None,
        "error": f"{code}: {detail}",
        "usage": None,
        "elapsedMs": elapsed if elapsed is not None else _elapsed_ms(started or time.monotonic()),
    }


def _elapsed_ms(started: float) -> int:
    return int((time.monotonic() - started) * 1000)
