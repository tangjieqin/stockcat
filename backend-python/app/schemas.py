"""内部接口的请求/响应模型（P1 历史行情）。

JSON 字段统一 camelCase，与 Java 侧 docs/API.md 的命名风格保持一致。
"""
from datetime import date as Date
from datetime import datetime

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
