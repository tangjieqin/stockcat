"""东方财富 push2his 日 K 适配器（P1）。

职责边界：只做「拼请求 + 映射字段 + 把上游异常翻译成明确错误码」。
复权是否可用于指标、非正值是否入库、当日未收盘 bar 是否拒写——全部由 Java 判定，
本层不做业务取舍，也不写任何数据库。

上游实测结论见 docs/DATA_SOURCES.md：
- fields2 返回的 CSV 第 3 列是**收盘价**（不是最高价），顺序为 日期,开,收,高,低,量,额,振幅,涨跌幅,涨跌额,换手率
- lmt 参数不生效（lmt=5 仍返回全量），只能用 beg/end 限定窗口，故按 limit 反推窗口再截尾
- rc=0 但 klines=[] 会真实发生（北交所等代码），不能当成「没有历史」静默成功
- rc=100 表示上游不识别该代码
- 前复权长期历史会出现负价（茅台 2001 年 -312.68），本层不修正、不丢弃，交由 Java 判定
"""
from __future__ import annotations

import json
import logging
import math
from dataclasses import dataclass
from datetime import date as Date
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

import httpx

from app.config import Settings, settings
from app.errors import (
    INVALID_UPSTREAM_DATA,
    NO_DATA,
    UPSTREAM_ERROR,
    UPSTREAM_TIMEOUT,
    ApiError,
)
from app.schemas import KlineBar

SOURCE_CODE = "eastmoney-push2his"
KLINE_PATH = "/api/qt/stock/kline/get"

_ADJUST_TO_FQT: dict[str, int] = {"raw": 0, "qfq": 1, "hfq": 2}
_KLINES: dict[str, int] = {"daily": 101}

# fields2 的顺序同时决定返回 CSV 的列顺序（务必与下面的列下标保持一致）
_FIELDS2 = "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61"
_COL_DATE = 0
_COL_OPEN = 1
_COL_CLOSE = 2
_COL_HIGH = 3
_COL_LOW = 4
_COL_VOLUME = 5
_COL_AMOUNT = 6
_COL_CHANGE_PERCENT = 8
_COL_TURNOVER = 10
_MIN_COLUMNS = 11

# limit 个交易日 ≈ limit*1.4 个自然日；取 2.0 倍冗余以覆盖长假，之后按 limit 截尾
_WINDOW_FACTOR = 2.0

_MARKET_TZ = ZoneInfo("Asia/Shanghai")

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class FetchResult:
    """一次取数的结果与元信息。"""

    secid: str
    symbol: str
    period: str
    adjust: str
    requested_limit: int
    fetched_at: datetime
    bars: list[KlineBar]
    skipped: int


class EastMoneyHistoryClient:
    def __init__(self, config: Settings | None = None, transport: httpx.BaseTransport | None = None) -> None:
        self._settings = config or settings
        self._client = httpx.Client(
            base_url=self._settings.history_base_url,
            timeout=self._settings.history_timeout_seconds,
            transport=transport,
            headers={"User-Agent": "StockCat/0.1"},
        )

    def close(self) -> None:
        self._client.close()

    def fetch_daily(
        self,
        secid: str,
        *,
        adjust: str = "qfq",
        limit: int = 250,
        end: Date | None = None,
    ) -> FetchResult:
        """取日 K（升序，最多 limit 根）。失败抛 ApiError，绝不返回空列表冒充成功。"""
        if adjust not in _ADJUST_TO_FQT:
            raise ApiError(400, "VALIDATION_ERROR", f"不支持的复权方式: {adjust}")
        if limit < 1:
            raise ApiError(400, "VALIDATION_ERROR", "limit 必须大于 0")
        if limit > self._settings.history_max_limit:
            raise ApiError(
                400,
                "VALIDATION_ERROR",
                f"limit 不能超过 {self._settings.history_max_limit}",
                [{"field": "limit", "reason": f"最大 {self._settings.history_max_limit}"}],
            )

        end_date = end or datetime.now(_MARKET_TZ).date()
        beg_date = end_date - timedelta(days=math.ceil(limit * _WINDOW_FACTOR))

        params = {
            "secid": secid,
            "fields1": "f1,f2,f3,f4,f5,f6",
            "fields2": _FIELDS2,
            "klt": _KLINES["daily"],
            "fqt": _ADJUST_TO_FQT[adjust],
            "beg": beg_date.strftime("%Y%m%d"),
            "end": end_date.strftime("%Y%m%d"),
        }

        payload = self._get_json(params)
        fetched_at = datetime.now(timezone.utc)
        raw_klines, upstream_name = self._extract_klines(payload)

        bars: list[KlineBar] = []
        skipped = 0
        seen: set[Date] = set()
        for row in raw_klines:
            bar = _parse_bar(row)
            if bar is None:
                skipped += 1
                continue
            if bar.date in seen:
                # 同一天出现多行时保留后出现的一行（上游正常不会这样）
                bars = [b for b in bars if b.date != bar.date]
                skipped += 1
            seen.add(bar.date)
            bars.append(bar)

        if not bars:
            raise ApiError(502, INVALID_UPSTREAM_DATA, f"上游日K数据无法解析（{upstream_name or secid}）")

        bars.sort(key=lambda b: b.date)
        bars = bars[-limit:]
        if skipped:
            log.warning("上游日K存在 %d 行无法解析或重复，标的 %s", skipped, secid)

        return FetchResult(
            secid=secid,
            symbol=secid.split(".", 1)[-1],
            period="daily",
            adjust=adjust,
            requested_limit=limit,
            fetched_at=fetched_at,
            bars=bars,
            skipped=skipped,
        )

    def _get_json(self, params: dict[str, object]) -> dict:
        try:
            resp = self._client.get(KLINE_PATH, params=params)
        except httpx.TimeoutException as exc:
            raise ApiError(
                504,
                UPSTREAM_TIMEOUT,
                f"上游行情源超时（>{self._settings.history_timeout_seconds}s）",
            ) from exc
        except httpx.HTTPError as exc:
            raise ApiError(502, UPSTREAM_ERROR, f"上游行情源请求失败: {exc}") from exc

        if resp.status_code != 200:
            raise ApiError(502, UPSTREAM_ERROR, f"上游行情源 HTTP {resp.status_code}")

        try:
            payload = resp.json()
        except (json.JSONDecodeError, ValueError) as exc:
            raise ApiError(502, UPSTREAM_ERROR, "上游行情源响应不是合法 JSON") from exc
        if not isinstance(payload, dict):
            raise ApiError(502, UPSTREAM_ERROR, "上游行情源响应结构异常")
        return payload

    @staticmethod
    def _extract_klines(payload: dict) -> tuple[list[str], str | None]:
        rc = payload.get("rc")
        if rc == 100:
            # 实测：非法/已切换代码返回 rc=100、data=null
            raise ApiError(404, NO_DATA, "上游不识别该证券代码")
        if rc != 0:
            raise ApiError(502, UPSTREAM_ERROR, f"上游返回 rc={rc}")

        data = payload.get("data")
        if not isinstance(data, dict):
            raise ApiError(404, NO_DATA, "上游无该证券的行情数据")

        name = data.get("name")
        raw_klines = data.get("klines")
        if not raw_klines:
            # 实测：北交所 0.835185 返回 rc=0、name="贝特瑞(已切换)"、klines=[]
            raise ApiError(404, NO_DATA, f"上游未返回日K数据（{name or '未知标的'}）")
        return list(raw_klines), name


def _parse_bar(raw: str) -> KlineBar | None:
    """解析一行 CSV；脏行返回 None 由调用方计数（不静默当成功）。"""
    parts = raw.split(",")
    if len(parts) < _MIN_COLUMNS:
        return None
    try:
        return KlineBar(
            date=Date.fromisoformat(parts[_COL_DATE]),
            open=float(parts[_COL_OPEN]),
            high=float(parts[_COL_HIGH]),
            low=float(parts[_COL_LOW]),
            close=float(parts[_COL_CLOSE]),
            volume=float(parts[_COL_VOLUME]),
            amount=float(parts[_COL_AMOUNT]),
            change_percent=_optional_float(parts[_COL_CHANGE_PERCENT]),
            turnover_rate=_optional_float(parts[_COL_TURNOVER]),
        )
    except (ValueError, IndexError):
        return None


def _optional_float(raw: str) -> float | None:
    try:
        return float(raw)
    except (TypeError, ValueError):
        return None


_default_client: EastMoneyHistoryClient | None = None


def get_default_client() -> EastMoneyHistoryClient:
    """FastAPI 依赖：进程内复用同一 httpx.Client（连接复用），测试可覆盖该依赖。"""
    global _default_client
    if _default_client is None:
        _default_client = EastMoneyHistoryClient()
    return _default_client


def close_default_client() -> None:
    global _default_client
    if _default_client is not None:
        _default_client.close()
        _default_client = None
