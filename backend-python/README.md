# StockCat Python 后端（P0 骨架）

FastAPI 服务。P0 阶段基本是空壳：
- 行情数据适配器（adapters/）P1 接入
- AI 复盘 Agent（LangChain）P2 接入

## 本地运行

```bash
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8100
```

- 健康检查: http://localhost:8100/health
- 接口文档: http://localhost:8100/docs
