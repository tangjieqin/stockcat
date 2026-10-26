"""天天基金 f10/lsjz 日终净值适配器（P1.4）。

职责边界与日 K 适配器一致：只做「拼请求 + 映射字段 + 把上游异常翻译成明确错误码」，
不判断溢价、不落库、不决定"净值日期该不该用"——这些属于 Java 业务层。

上游实测结论见 docs/DATA_SOURCES.md §3.2 / §7：
- 必须带 `Referer: https://fundf10.eastmoney.com/`，否则可能被拒
- 字段在 `Data.LSJZList`：FSRQ 净值日期、DWJZ 单位净值、LJJZ 累计净值、JZZZL 日增长率
- **非法代码返回 ErrCode=0 且 LSJZList 为空**（TotalCount=0）——不能当成"该基金没有净值"静默成功
- **pageSize 上限 20**：传 30/50 上游也只回 20 行（实测），要更多期数必须翻页
- T 日净值在当晚公布：2026-10-26 22:40 实测 510300 已有当日净值 4.1797
"""
from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from datetime import date as Date
from datetime import datetime, timezone

import httpx

from app.config import Settings, settings
from app.errors import (
    INVALID_UPSTREAM_DATA,
    NO_DATA,
    UPSTREAM_ERROR,
    UPSTREAM_TIMEOUT,
    ApiError,
)
from app.schemas import FundNavPoint

SOURCE_CODE = "eastmoney-f10-lsjz"
NAV_PATH = "/f10/lsjz"
REFERER = "https://fundf10.eastmoney.com/"

# 实测：单页最多回 20 行，pageSize=30/50 均无效
PAGE_SIZE_CAP = 20
# 防御：即使上游忽略 pageIndex 也不会无限翻页（250 期最多 13 页）
_MAX_PAGES = 20

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class NavFetchResult:
    """一次取数的结果与元信息（items 按日期倒序，与上游一致）。"""

    fund_code: str
    requested_limit: int
    fetched_at: datetime
    total_count: int
    items: list[FundNavPoint]
    skipped: int


class EastMoneyFundClient:
    def __init__(self, config: Settings | None = None, transport: httpx.BaseTransport | None = None) -> None:
        self._settings = config or settings
        self._client = httpx.Client(
            base_url=self._settings.fund_base_url,
            timeout=self._settings.fund_timeout_seconds,
            transport=transport,
            headers={
                "User-Agent": "StockCat/0.1",
                # 实测必需：缺 Referer 时上游可能返回空数据
                "Referer": REFERER,
            },
        )

    def close(self) -> None:
        self._client.close()

    def fetch_nav(self, fund_code: str, *, limit: int = 30) -> NavFetchResult:
        """取最近 limit 期净值（倒序）。失败抛 ApiError，绝不返回空列表冒充成功。"""
        if not fund_code or not fund_code.isdigit() or len(fund_code) != 6:
            raise ApiError(
                400,
                "VALIDATION_ERROR",
                f"fundCode 必须是 6 位数字: {fund_code}",
                [{"field": "fundCode", "reason": "6 位数字"}],
            )
        if limit < 1:
            raise ApiError(400, "VALIDATION_ERROR", "limit 必须大于 0")
        if limit > self._settings.fund_max_limit:
            raise ApiError(
                400,
                "VALIDATION_ERROR",
                f"limit 不能超过 {self._settings.fund_max_limit}",
                [{"field": "limit", "reason": f"最大 {self._settings.fund_max_limit}"}],
            )

        fetched_at = datetime.now(timezone.utc)
        items: list[FundNavPoint] = []
        skipped = 0
        seen: set[Date] = set()
        total_count = 0
        page = 1
        while len(items) < limit and page <= _MAX_PAGES:
            payload = self._get_json({
                "fundCode": fund_code,
                # 单页最多 20 行，多要也没用；靠 pageIndex 翻页
                "pageIndex": page,
                "pageSize": min(limit, PAGE_SIZE_CAP),
            })
            raw_items, total = self._extract_page(payload, fund_code, page)
            total_count = total or total_count
            if not raw_items:
                break

            new_rows = 0
            for row in raw_items:
                point = _parse_point(row)
                if point is None:
                    skipped += 1
                    continue
                if point.date in seen:
                    # 同一天重复：翻页边界偶尔会重叠，保留先出现的一行（上游倒序，先出现更新）
                    skipped += 1
                    continue
                seen.add(point.date)
                items.append(point)
                new_rows += 1

            # 上游忽略 pageIndex 或已到末页时，不要继续翻
            if new_rows == 0:
                break
            page += 1

        if not items:
            raise ApiError(502, INVALID_UPSTREAM_DATA, f"上游净值数据无法解析（{fund_code}）")
        if skipped:
            log.warning("上游净值存在 %d 行无法解析或重复，基金 %s", skipped, fund_code)

        items.sort(key=lambda p: p.date, reverse=True)
        return NavFetchResult(
            fund_code=fund_code,
            requested_limit=limit,
            fetched_at=fetched_at,
            total_count=total_count,
            items=items[:limit],
            skipped=skipped,
        )

    def _get_json(self, params: dict[str, object]) -> dict:
        try:
            resp = self._client.get(NAV_PATH, params=params)
        except httpx.TimeoutException as exc:
            raise ApiError(
                504,
                UPSTREAM_TIMEOUT,
                f"上游净值源超时（>{self._settings.fund_timeout_seconds}s）",
            ) from exc
        except httpx.HTTPError as exc:
            raise ApiError(502, UPSTREAM_ERROR, f"上游净值源请求失败: {exc}") from exc

        if resp.status_code != 200:
            raise ApiError(502, UPSTREAM_ERROR, f"上游净值源 HTTP {resp.status_code}")

        try:
            payload = resp.json()
        except (json.JSONDecodeError, ValueError) as exc:
            raise ApiError(502, UPSTREAM_ERROR, "上游净值源响应不是合法 JSON") from exc
        if not isinstance(payload, dict):
            raise ApiError(502, UPSTREAM_ERROR, "上游净值源响应结构异常")
        return payload

    @staticmethod
    def _extract_page(payload: dict, fund_code: str, page: int) -> tuple[list[dict], int]:
        """取一页。第 1 页空 → NO_DATA；后续页空 → 正常结束（返回空列表）。"""
        err_code = payload.get("ErrCode")
        if err_code not in (0, None):
            raise ApiError(502, UPSTREAM_ERROR, f"上游返回 ErrCode={err_code}（{payload.get('ErrMsg') or '无说明'}）")

        data = payload.get("Data")
        if not isinstance(data, dict):
            if page == 1:
                raise ApiError(404, NO_DATA, f"上游无该基金净值数据（{fund_code}）")
            return [], 0

        raw_items = list(data.get("LSJZList") or [])
        total = payload.get("TotalCount")
        total_count = int(total) if isinstance(total, int) else 0

        if not raw_items and page == 1:
            # 实测：非法代码 ErrCode=0、TotalCount=0、列表为空
            raise ApiError(404, NO_DATA, f"上游未返回净值数据，可能不是基金代码（{fund_code}）")

        return raw_items, total_count


def _parse_point(row: dict) -> FundNavPoint | None:
    """解析一条净值；脏行返回 None 由调用方计数（不静默当成功）。"""
    if not isinstance(row, dict):
        return None
    try:
        return FundNavPoint(
            date=Date.fromisoformat(str(row["FSRQ"])),
            nav=float(row["DWJZ"]),
            acc_nav=_optional_float(row.get("LJJZ")),
            change_percent=_optional_float(row.get("JZZZL")),
        )
    except (KeyError, TypeError, ValueError):
        return None


def _optional_float(raw: object) -> float | None:
    try:
        return float(raw)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return None


_default_client: EastMoneyFundClient | None = None


def get_default_client() -> EastMoneyFundClient:
    """FastAPI 依赖：进程内复用同一 httpx.Client，测试可覆盖该依赖。"""
    global _default_client
    if _default_client is None:
        _default_client = EastMoneyFundClient()
    return _default_client


def close_default_client() -> None:
    global _default_client
    if _default_client is not None:
        _default_client.close()
        _default_client = None
