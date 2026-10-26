"""内部历史行情接口（仅供 Java 服务调用，不对前端暴露）。

契约见 backend-python/README.md §内部接口。
"""
from datetime import date as Date

from fastapi import APIRouter, Depends, Query

from app.adapters.eastmoney_history import (
    SOURCE_CODE,
    EastMoneyHistoryClient,
    get_default_client,
)
from app.config import settings
from app.errors import NOT_SUPPORTED, VALIDATION_ERROR, ApiError
from app.schemas import KlineResponse

router = APIRouter(prefix="/internal/v1", tags=["internal"])

_SUPPORTED_PERIODS = ("daily",)
_SUPPORTED_ADJUSTS = ("raw", "qfq", "hfq")


@router.get("/kline", response_model=KlineResponse, summary="日 K 线")
def get_daily_kline(
    secid: str = Query(..., pattern=r"^[01]\.\d{6}$", description="东财 secid，如 1.600519"),
    period: str = Query("daily", description="周期，P1 仅 daily"),
    adjust: str = Query("qfq", description="raw / qfq / hfq"),
    limit: int = Query(250, ge=1, description="返回条数（升序尾部），上限 500"),
    end: Date | None = Query(None, description="截止交易日，默认今天（Asia/Shanghai）"),
    client: EastMoneyHistoryClient = Depends(get_default_client),
) -> KlineResponse:
    if period not in _SUPPORTED_PERIODS:
        raise ApiError(
            400,
            NOT_SUPPORTED,
            f"暂不支持的周期: {period}（P1 仅支持 daily）",
            [{"field": "period", "reason": "P1 仅支持 daily"}],
        )
    if adjust not in _SUPPORTED_ADJUSTS:
        raise ApiError(
            400,
            VALIDATION_ERROR,
            f"不支持的复权方式: {adjust}",
            [{"field": "adjust", "reason": "raw / qfq / hfq"}],
        )
    if limit > settings.history_max_limit:
        raise ApiError(
            400,
            VALIDATION_ERROR,
            f"limit 不能超过 {settings.history_max_limit}",
            [{"field": "limit", "reason": f"最大 {settings.history_max_limit}"}],
        )

    result = client.fetch_daily(secid, adjust=adjust, limit=limit, end=end)
    return KlineResponse(
        source=SOURCE_CODE,
        secid=result.secid,
        symbol=result.symbol,
        period=result.period,
        adjust=result.adjust,
        requested_limit=result.requested_limit,
        fetched_at=result.fetched_at,
        bar_count=len(result.bars),
        skipped_bars=result.skipped,
        bars=result.bars,
    )
