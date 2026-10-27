# StockCat

个人股票/基金行情监控、告警与 AI 复盘工具。当前处于 **P0 开发阶段（本地单机版）**。

## 目录结构

- `docs/`：设计文档（PRD / UI / ARCHITECTURE / DATABASE / API / DEVELOPMENT_PLAN + prototype.html 原型）
- `backend-java/`：Java 后端（Spring Boot 3 + Java 21 + MyBatis + Flyway），业务权威服务
- `backend-python/`：Python 服务（FastAPI），P1 行情适配 / P2 AI 复盘
- `frontend/`：前端（Vue 3 + Vite + TS + Pinia）
- `docker-compose.yml`：仅 PostgreSQL 一个服务

## 本地启动

```bash
# 1. 数据库（Docker）
docker compose up -d

# 2. Java 后端（local 已是默认 profile，Flyway 自动建表/迁移；IDEA 直接运行主类亦可）
cd backend-java
mvn spring-boot:run -DskipTests

# 3. 前端
cd frontend
npm install
npm run dev    # http://localhost:5173

# 4. Python 适配器（只有历史 K 线同步 / P2 Agent 需要，可选）
cd backend-python
python -m venv .venv && .venv\Scripts\activate   # Windows；macOS/Linux 用 source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8100
```

**两个后端是独立的两个进程，互不依赖启动顺序**：

| 进程 | 端口 | 职责 | 不启动会怎样 |
|---|---|---|---|
| `backend-java` | 8081 | 业务权威：行情采集、告警、持仓、复盘、基金 IOPV（腾讯直连） | 前端全部不可用 |
| `backend-python` | 8100 | 历史行情与基金净值抓取（P1）/ AI 助手 Agent（P2） | 实时行情、告警、IOPV、持仓**照常**；只有历史 K 线与净值同步返回 `DATA_SOURCE_UNAVAILABLE`（净值缺失时 LOF 溢价不显示，ETF 溢价仍可用 IOPV），AI 助手页面提示「AI 服务未启动」 |

Java 在两个场景通过 `http://127.0.0.1:8100/internal/v1/*` 调 Python：同步历史 K 线（`/kline`）与同步基金净值（`/fund-nav`），
配置项 `stockcat.history-source.base-url`。AI 助手走反方向：前端 → Java `POST /api/v1/assistant/ask` → Python `POST /agent/v1/assistant/ask`
（配置项 `stockcat.assistant.base-url`，默认同为 `http://127.0.0.1:8100`）。不想再开一个终端时，可以先只跑 Java + 前端做 P0 的全部功能，
需要看 K 线图、LOF 净值溢价或 AI 助手时再起 Python。

环境变量（均可用默认值）：`STOCKCAT_DB_HOST/PORT/NAME/USER/PASSWORD`，见 `.env.example`。通知推送：在 `.env` 中配置 `stockcat.notify.serverchan.send-key=<你的SendKey>`（sct.ftqq.com 免费申请，推送到微信「方糖」服务号），在「设置-通知」页可查看渠道状态并发送测试通知。

### AI 助手（可选）

在 Python 侧配置三个环境变量即可启用模型分析（OpenAI 兼容协议，DeepSeek / 通义 / Kimi / OpenAI / 本地 Ollama 都适用）：

```bash
STOCKCAT_LLM_BASE_URL=https://api.deepseek.com/v1
STOCKCAT_LLM_MODEL=deepseek-chat
STOCKCAT_LLM_API_KEY=sk-...
```

- **不配置也能用**：页面会标注「规则汇总模式」，回答由真实数据拼成，只是没有模型写的分析段落；不会伪造 AI 结果。
- API Key 只放服务端环境变量，前端与接口都不回显；`GET /api/v1/assistant/status` 只报「是否已配置」。
  **页面里没有填 Key 的输入框**：能在浏览器填的 key，迟早会被存到浏览器里。
- 配置是否生效、AI 能查哪些数据、三个场景各自怎么降级，都写在侧边栏「其他 · **AI 配置**」页（只读状态页），
  不用翻文档；`GET /api/v1/system/status` 里也有同一份状态（`pythonService`）。
- AI 只读业务数据：只能调用 `docs/API.md §10` 的白名单工具，不改流水、不改规则、不发通知。
  唯一的写入是**复盘草稿**，而且必须由用户点「保存为草稿」才落库，状态固定 `AI_DRAFT`；
  同一天已有正式复盘时直接 `409`，AI 永不覆盖人工内容（详见 `docs/PLAN_P2.md §0.3`）。
- 每次调用写一行日志（logger `stockcat.agent`）：场景、模式、模型、耗时、工具成败、token、错误码。
  不含 Key、不含提问与回答原文；格式见 `docs/API.md §11`。要排查"为什么这次没有分析"，先看这行。

已落地的三个 AI 场景：

| 场景 | 入口 | 说明 |
|---|---|---|
| AI 助手（自由提问） | 侧边栏「AI 助手」/ 首页卡片 | 关键词选工具 → 取真实数据 → 模型写结论；回答分「结论 / 数据依据 / 执行过程 / 数据限制」四块 |
| 告警 AI 解释 | 告警历史每行 [AI 解释] | 右侧抽屉四段：为什么触发 / 这意味着什么 / 当前状态 / 数据依据。**事件快照没保存的指标不会回算**，会明确写"没有保存"；此时不启用模型改写 |
| AI 复盘草稿 | 市场复盘页「✨ AI 生成草稿」 | 按当天数据生成四段草稿（大盘/自选/持仓/告警），弹窗内可改；顶部固定展示生成时间、数据截至与数据限制。保存为 `AI_DRAFT`，用户编辑保存后才转 `MANUAL`；没有行业板块数据时只按自选分组汇总并明确标注 |

## 交易日历（P0.4）

交易日/时段判断逻辑：`trading_calendar` 表记录优先 → 回退「周一至周五 + 配置覆盖」。

- **数据来源与更新方式**：P0 回退规则只识别周末，**不识别法定节假日与调休**。两种维护方式：
  1. 向 `trading_calendar` 表插入年度数据（`market='CN_A'`，每交易日一条，来源需在 `source_code` 记录）；
  2. 在配置中维护：`stockcat.calendar.extra-holidays=2026-10-01,...`（假日）与 `extra-workdays=...`（调休上班日）。
- **失效处理**：若两者都未维护，回退规则会把法定节假日当作交易日——调度器仍只会在配置的采集窗口内运行，行情源返回的数据会按行情时间正确标记 `STALE`，不会伪造有效行情。**建议每年度更新一次节假日数据。**
- 采集窗口默认 `09:25-11:35,12:55-15:10`（Asia/Shanghai），间隔默认 15 秒，均可配置：
  `stockcat.calendar.collect-windows` / `stockcat.collect.interval-seconds` / `stockcat.collect.enabled`。

## 行情数据源

- `eastmoney-push2`（东方财富 push2 批量接口）：P0 已验证可用，仅支持沪深 A 股代码（6/5/9 沪、0/1/2/3 深）；北交所暂不支持。
- 过期判定：行情时间距今超过 `stockcat.quote.stale-after-minutes`（默认 30 分钟）标记 `STALE`；缺失字段一律 null，不按 0 显示。

## 历史数据（P1）

日 K 由 Python 抓取、Java 校验并落库（抓取在 Python，业务判定在 Java）。

- **自动同步**：交易日 15:35（`stockcat.history.sync.cron`）同步「自选 ∪ 启用中的监控标的 ∪ 当前持仓」最近 120 根日 K。
- **手动补跑**：`POST /api/v1/history/sync?days=250`（全部范围）或 `POST /api/v1/instruments/{id}/history/sync?days=250`。
- **写入规则**：`UNIQUE(instrument_id, trade_date, adjust)` + UPSERT，可反复跑；前复权历史值会随除权整体移动，因此必须覆盖写。
- **拒写规则**（避免"看着有数、其实是错的"）：
  - 当日 K 线未收盘（15:05 前）——上游盘中会返回半根 K 线；
  - 价格非正——前复权长窗口会把早期价格算成负数（实测茅台 2001-08-27 为 -312.68）；
  - 单次上限 500 根，默认 120 根（覆盖 MACD 预热需求）。
- **失败隔离**：Python 未启动/上游超时只记一条 `FAILED` + `DATA_SOURCE_UNAVAILABLE`，不影响实时采集与告警；
  上游"HTTP 成功但 0 条"也记为失败（`NO_DATA`），不会伪装成同步成功。
- **技术指标**：`GET /api/v1/instruments/{id}/indicators?adjust=qfq&limit=250`，返回 MACD(12,26,9)、RSI(6/12/24)、KDJ(9,3,3)。
  指标**不落库**、按查询现算；口径与通达信一致（RSI 用 SMA(X,N,1)、KDJ 的 K/D 用 SMA 递推、MACD 柱 ×2），
  因此没有引入 ta4j —— 实测它的 RSI6 与看盘软件差 13 个点。预热不足的字段返回 `null` 而不是 0。

## 排障（FAQ）

| 现象 | 排查 |
|---|---|
| Java 启动报连接数据库失败 | 确认 `docker compose up -d` 已跑、容器 healthy（`docker ps`） |
| Flyway 校验失败 | 不要修改已应用的迁移文件；新变更新增 `Vn__xxx.sql` |
| 前端请求 404/代理失败 | 确认 Java 已启动（8081）；Vite 代理 `/api → 8081` 见 `vite.config.ts`，可用环境变量 `STOCKCAT_API_TARGET` 指到其他端口（如另起的 8082 验证实例） |
| 页面价格显示 `—` | 行情缺失或过期（不按 0 显示）；点「刷新行情」或等调度器（交易时段 09:25-11:35/12:55-15:10） |
| 数据显示「数据过期」 | 行情时间距今超过 `stockcat.quote.stale-after-minutes`（默认 30 分钟），周末/节假日属正常 |
| 告警没触发 | 规则需启用；仅交易时段评估；仅 VALID 行情触发；检查是否在冷却期（默认 30 分钟） |
| 指标规则（MACD/KDJ/RSI）没提醒 | 指标规则只在收盘后 15:40 按日线评估，且是"穿越"语义——已在金叉状态不会补发；当天已提醒过会被去重；K 线不够也不会触发。执行 `POST /api/v1/alert-rules/indicator/evaluate` 可看到每条规则的具体状态 |
| 收益曲线是空的 | 快照每天 15:45 生成，也可点「重建快照」；重建会先补齐**不复权**日线，所以需要 Python（8100）在线，否则该标的市值留空、页面提示"缺收盘价" |
| 溢价规则建不了 | `PREMIUM_*` 只对 ETF/LOF 开放（股票没有 IOPV/单位净值）；LOF 的净值超过 3 天未更新时也不会判定，避免拿过期净值当实时溢价 |
| 卖出被拒 409 | 卖出数量超过当时持有量，message 里有当前可卖数量 |
| 删除交易流水 409 | 删除该买入会使其后的卖出在重放时超卖，属数据保护 |
| IDEA 运行报 `sqlSessionFactory or sqlSessionTemplate required` | 运行配置里不要额外设置 `--spring.profiles.active=default`；P0.2 起数据库是硬依赖，默认 profile 已是 local（连接 PostgreSQL） |

## 当前进度

- ✅ P0.1 项目骨架（三端可启动 + Flyway 建表 + 统一错误结构）
- ✅ P0.2 标的主数据与监控标的
- ✅ P0.3 自选与实时行情（东财数据源 + 快照 + 手动刷新）
- ✅ P0.4 交易日历与采集调度（防重入 + 内置指数 + 状态统计）
- ✅ P0.5 告警规则与事件（四种评估器 + 冷却 + 去重 + 快照）
- ✅ P0.6 通知链路（Server酱 → 微信推送；测试发送已验证 SENT，失败可重试，重试仅限 FAILED）
- ✅ P0.7 交易流水与持仓（重放计算 + 超卖校验）
- ✅ P0.8 复盘与我的日记（分区复盘 + 树状笔记本）
- ✅ P0.9 首页总览与集成验收（dashboard 聚合 + 告警总览 + 全链路演示）
- ✅ P1.0 数据源实测（前复权负价、`lmt` 失效、盘中半根 K 线、`rc=0` 假成功、LOF 无实时 IOPV）
- ✅ P1.1 Python 历史适配器（`GET /internal/v1/kline` + 27 个 pytest）
- ✅ P1.2 Java 历史落库与同步（`kline_daily` + 收盘后同步 + 查询/同步 API + 42 个单测）
- ✅ P1.3 技术指标（MACD/RSI/KDJ，口径对齐通达信；`GET /instruments/{id}/indicators`，现算不落库 + 63 个单测）
- ✅ P1.6 指标类告警（MACD/KDJ 穿越 + RSI 穿越阈值；收盘后 15:40 评估、按交易日去重 + 81 个单测）
- ✅ P1.7 标的详情页 K 线图（ECharts 多 pane：K 线/成交量/MACD/RSI/KDJ；未同步、数据不足、数据源不可用全状态；+ 83 个 Java 单测）
- ✅ P1.4 基金净值/IOPV 与溢价（腾讯 IOPV 实时 + 天天基金净值带日期；溢价查询层现算并标注基准与滞后天数；自选溢价列 + 详情基金区块；+ 108 个 Java 单测、44 个 pytest）
- ✅ P1.5 收益曲线与持仓变化 + 溢价告警（每日持仓快照由流水重放 + 不复权收盘价生成、可整段重建；`/portfolio/*` 三个接口；持仓页三页签；`PREMIUM_ABOVE/BELOW` 盘中评估、仅 ETF/LOF；+ 129 个 Java 单测）

