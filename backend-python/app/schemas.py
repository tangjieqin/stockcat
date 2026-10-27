"""内部接口的请求/响应模型（P1 历史行情）。

JSON 字段统一 camelCase，与 Java 侧 docs/API.md 的命名风格保持一致。
"""
from datetime import date as Date
from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class KlineBar(CamelModel):
    """一根日 K。

    close / volume 等字段一律按上游原样返回；非正值不在此过滤，
    由 Java 决定是否入库（口径判断属于业务层）。
    """

    date: Date = Field(description="交易日")
    open: float
    high: float
    low: float
    close: float
    volume: float = Field(description="成交量，单位：手（1 手 = 100 股/份）")
    amount: float = Field(description="成交额，单位：元")
    change_percent: float | None = Field(default=None, description="涨跌幅，百分数值")
    turnover_rate: float | None = Field(default=None, description="换手率，百分数值")


class KlineResponse(CamelModel):
    source: str = Field(description="数据源标识，写入 kline_daily.source_code")
    secid: str
    symbol: str
    period: str
    adjust: str = Field(description="raw / qfq / hfq")
    requested_limit: int
    fetched_at: datetime = Field(description="服务端取数时间（UTC）")
    bar_count: int
    skipped_bars: int = Field(description="上游脏行数（无法解析而被跳过），非 0 表示数据不完整")
    bars: list[KlineBar]


class FundNavPoint(CamelModel):
    """一期净值（P1.4）。

    nav 是**当日公布的单位净值**；净值日期就是 date，不在这里推断"是 T 还是 T-1"——
    Java 侧据 date 与实际交易日比较后给出 staleDays。
    """

    date: Date = Field(description="净值日期（上游 FSRQ）")
    nav: float = Field(description="单位净值（DWJZ）")
    acc_nav: float | None = Field(default=None, description="累计净值（LJJZ）")
    change_percent: float | None = Field(default=None, description="日增长率，百分数值（JZZZL）")


class FundNavResponse(CamelModel):
    source: str = Field(description="数据源标识，写入 fund_reference.source_code")
    fund_code: str
    requested_limit: int
    fetched_at: datetime = Field(description="服务端取数时间（UTC）")
    total_count: int = Field(description="上游可返回的总期数，用于判断历史深度")
    count: int
    skipped_rows: int = Field(description="上游脏行数，非 0 表示数据不完整")
    items: list[FundNavPoint] = Field(description="按日期倒序，与上游一致")


# --------------------------------------------------------------- P2 Agent


class AgentAskRequest(CamelModel):
    question: str = Field(min_length=1, description="用户问题")


class AgentAnswer(CamelModel):
    """回答的四个部分：结论、数据依据、数据限制（执行过程在 toolCalls 里）。"""

    conclusion: str
    evidence: list[str] = Field(default_factory=list, description="引用的具体数据事实")
    limits: list[str] = Field(default_factory=list, description="数据时间与缺失项说明")


class AgentToolCall(CamelModel):
    tool: str = Field(description="工具名，与 Java /api/v1/agent/tools 白名单一致")
    label: str = Field(description="给用户看的中文动作名")
    ok: bool
    summary: str | None = Field(default=None, description="结果摘要，如「1 只持仓」")
    error: str | None = Field(default=None, description="失败原因（ok=false 时）")
    elapsed_ms: int | None = None
    data_as_of: str | None = None


class AgentUsage(CamelModel):
    model: str
    prompt_tokens: int | None = None
    completion_tokens: int | None = None


class AgentCitation(CamelModel):
    """一条资料引用。``citation`` 是后端拼好的「《标题》· 第 N 段」，前端不重新拼。"""

    chunk_id: int
    document_id: int
    title: str
    seq: int
    citation: str


class AgentAskResponse(CamelModel):
    question: str
    mode: str = Field(description="llm = 模型生成；rule_based = 规则汇总（未配置模型或模型失败）")
    answer: AgentAnswer
    tool_calls: list[AgentToolCall]
    citations: list[AgentCitation] = Field(
        default_factory=list,
        description="资料库引用（P3.3）。chunkId/documentId/seq 由 Java 工具返回，前端据此跳原文")
    data_as_of: str | None = Field(default=None, description="整段回答的数据截至时间")
    generated_at: str
    model: str | None = None
    usage: AgentUsage | None = None
    degraded: list[str] = Field(default_factory=list, description="降级原因，非空时前端要显示")
    elapsed_ms: int


class AgentHealthResponse(CamelModel):
    status: str
    model_configured: bool
    model: str | None = Field(default=None, description="模型名；不回显 key")
    tools: list[str] = Field(description="可调用的只读工具白名单")


# --------------------------------------------------- AI 每日复盘草稿（P2 场景三）


class AgentRecapDraftRequest(CamelModel):
    date: Date | None = Field(default=None, description="复盘归属日期；缺省为今天（Asia/Shanghai）")


class AgentRecapDraft(CamelModel):
    title: str
    market_summary: str = Field(description="市场主要情况")
    sector_notes: str = Field(description="板块表现（本项目无板块数据时写明只按自选分组汇总）")
    content: str = Field(description="Markdown 正文")


class AgentRecapDraftResponse(CamelModel):
    date: Date
    mode: str = Field(description="llm = 模型成文；rule_based = 规则底稿（未配置模型或模型失败）")
    draft: AgentRecapDraft
    limitations: list[str] = Field(description="数据限制，恒由模板生成，模型不能增删")
    generated_at: str
    data_as_of: str | None = Field(default=None, description="草稿数据的观测时点")
    tool_calls: list[AgentToolCall]
    model: str | None = None
    usage: AgentUsage | None = None
    degraded: list[str] = Field(default_factory=list)
    elapsed_ms: int


# ------------------------------------------------------- 告警 AI 解释（P2 场景二）


class AgentAlertEventSnapshot(CamelModel):
    """事件快照：**唯一**可引用的事实来源。缺什么就说什么，不回算。"""

    id: int
    symbol: str
    name: str | None = None
    rule_name: str
    rule_type: str
    params: dict[str, Any] = Field(default_factory=dict, description="规则参数快照")
    observed_price: float | None = None
    observed_change_percent: float | None = None
    observed_detail: dict[str, Any] | None = Field(
        default=None, description="指标值与溢价明细快照；行情类规则可能为 null"
    )
    triggered_at: datetime
    quote_market_time: datetime | None = None


class AgentAlertCurrentState(CamelModel):
    """当前状态：与事件快照严格分开，用于回答「现在怎么样了」。"""

    last_price: float | None = None
    change_percent: float | None = None
    data_status: str | None = Field(default=None, description="VALID / STALE / PARTIAL")
    market_time: datetime | None = None
    collected_at: datetime | None = None
    latest_trade_date: Date | None = None
    indicators: dict[str, float | None] | None = Field(
        default=None, description="最新已收盘日线的指标值；仅指标类规则会带"
    )
    bars_available: int = Field(default=0, description="可用日 K 根数，用于解释指标为何缺失")


class AgentAlertExplainRequest(CamelModel):
    event: AgentAlertEventSnapshot
    current: AgentAlertCurrentState | None = None


class AgentAlertExplainResponse(CamelModel):
    event_id: int
    symbol: str | None = None
    name: str | None = None
    rule_name: str | None = None
    rule_type: str | None = None
    rule_type_label: str | None = None
    condition: str | None = Field(default=None, description="触发条件文案，如「价格 ≥ ¥1000」")
    mode: str = Field(description="llm = 模型改写措辞；rule_based = 模板生成")
    why: str = Field(description="为什么触发")
    meaning: str = Field(description="这意味着什么（含指标局限）")
    current: str = Field(description="当前状态；无数据时说明缺什么")
    evidence: list[str] = Field(default_factory=list, description="数据依据，恒由模板生成")
    limits: list[str] = Field(default_factory=list, description="数据限制，恒由模板生成")
    snapshot_complete: bool = Field(description="事件快照是否足以复述触发依据")
    generated_at: str
    model: str | None = None
    usage: AgentUsage | None = None
    degraded: list[str] = Field(default_factory=list)
    elapsed_ms: int

