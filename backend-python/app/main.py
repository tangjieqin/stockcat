"""StockCat Python 后端入口。

P0: 仅健康检查。业务数据一律由 Java 服务负责，Python 不直连业务数据库。
"""
from fastapi import FastAPI

app = FastAPI(
    title="StockCat Python Service",
    version="0.1.0",
    description="行情数据适配（P1）与 AI 复盘（P2）。P0 为空壳。",
)


@app.get("/health")
def health() -> dict:
    return {"status": "UP", "service": "stockcat-python", "version": "0.1.0"}

