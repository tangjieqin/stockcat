"""StockCat Python 后端入口。

职责：P1 历史行情适配（日 K / 日终净值），P2 AI 复盘。
不直连业务数据库，不写业务表——业务数据一律由 Java 服务负责。
"""
from contextlib import asynccontextmanager
import logging

from fastapi import FastAPI

from app.adapters.eastmoney_fund import close_default_client as close_fund_client
from app.adapters.eastmoney_history import close_default_client
from app.agent.java_tools import close_client as close_java_client
from app.api.agent import router as agent_router
from app.api.fund_nav import router as fund_nav_router
from app.api.kline import router as kline_router
from app.errors import register_exception_handlers

# AI 运行日志（app/agent/audit.py）走 root logger。uvicorn 只配置自己的几个 logger、
# 不碰 root，不显式配一下的话 INFO 会被丢掉（只剩 lastResort 的 WARNING）。
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)


@asynccontextmanager
async def lifespan(_: FastAPI):
    yield
    close_default_client()
    close_fund_client()
    close_java_client()


app = FastAPI(
    title="StockCat Python Service",
    version="0.4.0",
    description="历史行情与基金净值适配（P1）与 AI 复盘（P2）。内部接口仅供 Java 调用。",
    lifespan=lifespan,
)

register_exception_handlers(app)
app.include_router(kline_router)
app.include_router(fund_nav_router)
app.include_router(agent_router)


@app.get("/health")
def health() -> dict:
    return {"status": "UP", "service": "stockcat-python", "version": "0.4.0"}
