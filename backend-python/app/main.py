"""StockCat Python 后端入口。

职责：P1 历史行情适配（日 K / 日终净值），P2 AI 复盘。
不直连业务数据库，不写业务表——业务数据一律由 Java 服务负责。
"""
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.adapters.eastmoney_fund import close_default_client as close_fund_client
from app.adapters.eastmoney_history import close_default_client
from app.api.fund_nav import router as fund_nav_router
from app.api.kline import router as kline_router
from app.errors import register_exception_handlers


@asynccontextmanager
async def lifespan(_: FastAPI):
    yield
    close_default_client()
    close_fund_client()


app = FastAPI(
    title="StockCat Python Service",
    version="0.3.0",
    description="历史行情与基金净值适配（P1）与 AI 复盘（P2）。内部接口仅供 Java 调用。",
    lifespan=lifespan,
)

register_exception_handlers(app)
app.include_router(kline_router)
app.include_router(fund_nav_router)


@app.get("/health")
def health() -> dict:
    return {"status": "UP", "service": "stockcat-python", "version": "0.3.0"}
