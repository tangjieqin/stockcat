"""内部基金净值接口（仅供 Java 服务调用，不对前端暴露）。

契约见 backend-python/README.md §内部接口。
"""
from fastapi import APIRouter, Depends, Query

from app.adapters.eastmoney_fund import (
    SOURCE_CODE,
    EastMoneyFundClient,
    get_default_client,
)
from app.config import settings
from app.errors import VALIDATION_ERROR, ApiError
from app.schemas import FundNavResponse

router = APIRouter(prefix="/internal/v1", tags=["internal"])


@router.get("/fund-nav", response_model=FundNavResponse, summary="基金日终净值")
def get_fund_nav(
    fund_code: str = Query(..., alias="fundCode", pattern=r"^\d{6}$", description="6 位基金代码，如 510300"),
    limit: int = Query(30, ge=1, description="返回期数（最近 N 期，倒序），上限 250"),
    client: EastMoneyFundClient = Depends(get_default_client),
) -> FundNavResponse:
    if limit > settings.fund_max_limit:
        raise ApiError(
            400,
            VALIDATION_ERROR,
            f"limit 不能超过 {settings.fund_max_limit}",
            [{"field": "limit", "reason": f"最大 {settings.fund_max_limit}"}],
        )

    result = client.fetch_nav(fund_code, limit=limit)
    return FundNavResponse(
        source=SOURCE_CODE,
        fund_code=result.fund_code,
        requested_limit=result.requested_limit,
        fetched_at=result.fetched_at,
        total_count=result.total_count,
        count=len(result.items),
        skipped_rows=result.skipped,
        items=result.items,
    )
