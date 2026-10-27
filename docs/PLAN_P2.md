# StockCat P2 开发计划（AI 每日复盘 Agent）

- 文档版本：V1.0-Draft（2026-10-27）
- 目标：把 AI 接进「每日复盘」这一条闭环，产出**草稿**，用户确认后才成为正式复盘。
- 原则：AI 只读、只写草稿；Python 不连数据库；模型不可用不影响 Java 采集与告警。

## 0. 现状盘点（写计划前实际核对过）

| 项 | 现状 | 对 P2 的意义 |
|---|---|---|
| `market_recap.status` | V1 已定义 `MANUAL / AI_DRAFT` | 草稿落点现成，只差生成与确认流程 |
| `market_recap` 唯一约束 | `UNIQUE(recap_date)` 一天一篇 | AI 生成需处理「当天已有」冲突，默认不覆盖 MANUAL |
| 日记树 `diary_node` | 树状 + 深度/成环校验已完成 | P2 只做「另存为日记」的后续可选能力 |
| Java 只读数据 | 自选行情、持仓、告警事件、复盘、日记、日 K、指标齐全 | 工具所需数据都已在 Java 侧，**不需要新表**（除标注字段） |
| Python 服务 | 只有 `/health`、`/internal/v1/kline`、`/internal/v1/fund-nav` | 需新增 `app/agent/` 模块，不引动数据适配器 |
| 前端 | 市场复盘页、日记页已存在；侧边栏「AI 配置」仍是 P2 占位 | 只需在复盘页加 AI 入口 + 草稿确认态 |
| 契约草案 | API.md §10 已写 `POST /agent/v1/chat` 雏形 | 按本计划细化，字段以本文为准 |

## 1. 关键决策（已按推荐默认值写死，可随时改）

| # | 决策 | 默认取值 | 理由 / 改动成本 |
|---|---|---|---|
| D1 | 模型接入 | **OpenAI 兼容协议**：`LLM_BASE_URL` + `LLM_MODEL` + `LLM_API_KEY` | 同一套代码可换 DeepSeek / 通义 / Kimi / OpenAI / 本地 Ollama；不锁厂商 |
| D2 | 草稿落点 | **市场复盘 `market_recap`，`status=AI_DRAFT`** | 一天一篇 + 状态机 + 唯一约束，语义最贴合「每日复盘」；比塞进日记树更可控 |
| D3 | 交互形态 | **一次性生成**（点按钮→等待→出完整草稿） | 先跑通闭环；流式（SSE）留到 P2.3 之后单独做 |
| D4 | 数据来源 | Agent **只通过 Java 只读工具**取数 | 保证「Python 不碰数据库」，口径与页面一致 |
| D5 | 工具调用方式 | LangChain tool calling；模型不支持时降级为「一次性取数 + 结构化填充」 | 兼容小模型/本地模型 |
| D6 | 冲突策略 | 当天已有 `MANUAL` 复盘 → 不生成（409 语义，前端提示「今天已写过」）；已有 `AI_DRAFT` → 允许重新生成并覆盖草稿 | AI 永不覆盖人的内容 |

## 2. 任务分解（每个任务结束都能运行、可测）

### P2.0 骨架与配置（S）

- `requirements.txt` 增 `langchain`、`langchain-openai`（锁版本，避免 API 漂移）。
- `app/config.py` 增 `llm_base_url` / `llm_model` / `llm_api_key` / `llm_timeout_seconds` / `java_base_url`。
- 新增 `GET /agent/v1/health`：返回 `{status, modelConfigured, model, baseUrlReachable:false}`——**不发真实请求、不回显 key**。
- 新增 `.env.example` 条目；README 补「AI 可选，未配置时该功能返回 `LLM_NOT_CONFIGURED`」。

验收：未配置 key 时 `/agent/v1/health` 明确报 `modelConfigured=false`；Python 其余接口零影响。

### P2.1 Java 受控只读工具 API（M）

新增 `web/AgentToolController`，统一前缀 `/api/v1/agent/tools`，**全部 GET、全部只读、无任何写接口**：

| 工具 | 返回要点 |
|---|---|
| `GET /watchlist-quotes` | 自选代码/名称/最新价/涨跌幅/行情时间/状态（数据过期要带 `dataStatus`） |
| `GET /positions` | 数量、摊薄成本、市值、浮盈、已实现盈亏 |
| `GET /alert-events?limit=10` | 最近事件：时间、标的、规则、通知状态 |
| `GET /market-brief?date=` | 当日指数行情 + 自选涨跌统计（给模型一个"市场概况"骨架） |
| `GET /recaps?from=&to=` | 历史复盘摘要（给模型做前后文对照，限制条数） |
| `GET /diary/search?keyword=` | 日记命中片段（可选，后续「结合日记写复盘」用） |

统一包装结构（便于模型理解，也便于前端展示「数据时间」）：

```json
{"tool":"positions","dataAsOf":"2026-10-27T07:00:00Z","data":{...},"missing":["510300 缺当日收盘价"]}
```

验收：每个工具一个单测；工具只读（用测试断言不存在写路径）；数据缺失时进 `missing` 而不是补 0。

### P2.2 Python Agent 工具封装（M）

- 新增 `app/agent/tools.py`：把 P2.1 的接口包成 LangChain `StructuredTool`（名称、描述、参数 schema 手写，**不靠模型猜**）。
- `app/agent/client.py`：httpx 调 Java，超时/重试（GET 可重试 1 次），错误映射 `JAVA_UNAVAILABLE`。
- 白名单在代码里硬编码，新增工具必须改代码→天然「只能调用白名单工具」。

验收：mock transport 下单测工具调用与错误映射；Python 不需要连 PG。

### P2.3 复盘草稿生成（M）— P2 的核心

- `POST /agent/v1/recap/draft`：请求 `{"date":"2026-10-27"}`；内部：拉工具数据 → 组装 prompt → 模型输出**结构化 JSON**（title / marketSummary / sectorNotes / content）→ 校验 → 返回。
- Prompt 约束（必须写进代码注释与文档）：
  - 数值只能来自工具返回，**禁止推测**；缺数据就写「数据缺失」，不补 0；
  - 不输出买卖建议，只描述事实与变化；
  - 输出必须能被 JSON Schema 解析，否则报 `MODEL_OUTPUT_INVALID` 让用户重试，不猜。
- 返回体：

```json
{
  "draft": {"title": "...", "marketSummary": "...", "sectorNotes": "...", "content": "..."},
  "generatedAt": "2026-10-27T03:30:00Z",
  "dataAsOf": "2026-10-27T03:00:00Z",
  "limitations": ["510300 的净值尚未更新", "指数行情为采集时点快照"],
  "toolCalls": [{"tool": "positions", "ok": true}],
  "usage": {"model": "deepseek-chat", "promptTokens": 0, "completionTokens": 0}
}
```

验收：模型输出非法 JSON 有明确错误、不落库；无工具数据时返回 `NO_DATA` 而不是编一段。

### P2.4 草稿落库与确认闭环（M）

- `POST /api/v1/recaps/drafts`：写入 `status=AI_DRAFT`，同一天已有 `MANUAL` → `409`（body 说明「今天已写过正式复盘」）；已有 `AI_DRAFT` → 覆盖草稿。
- 现有 `PATCH /recaps/{id}` 沿用：编辑 `AI_DRAFT` 保存后转 `MANUAL`（API.md §7 已有此约定，补齐实现与测试）。
- V8 迁移（推荐）：`market_recap` 加 `ai_generated_at`、`ai_model`、`ai_data_as_of`、`ai_limitations JSONB`——「生成时间与数据范围」要落库，不能只存在返回体里。

验收：AI_DRAFT 不出现在「正式复盘」列表筛选里（或明确标记）；PATCH 后状态流转正确；MANUAL 不可被 AI 覆盖（单测断言）。

### P2.5 前端 AI 复盘页（M）

- 市场复盘页加「✨ AI 生成草稿」按钮；三态：生成中（含耗时提示）/ 失败（显示错误码与可重试）/ 成功。
- 草稿确认页：分区编辑 `marketSummary` / `sectorNotes` / `content`；顶部固定展示「生成时间 / 数据截至 / 数据限制」，不可编辑、不可省略。
- 当天已有 MANUAL 时按钮置灰并说明原因（「今天已写过正式复盘，AI 草稿不会覆盖」）。
- 侧边栏「AI 配置」占位页改为：显示模型名/配置状态（只读，key 只显示是否已配置）。

验收：断网/停 Python 时按钮报「AI 服务不可用」而页面其余部分正常；草稿未确认不入正式列表。

### P2.6 降级、超时与可观测（S）

- 超时：整体 60s 上限，Java 工具单次 5s；**生成失败不自动重试**（避免双计费），由用户点重试。
- 日志：记 `date / model / 耗时 / 工具调用列表 / token 用量 / 错误码`，**不记 key、不记完整 prompt 原文**。
- Python 不可用：Java 侧 `/system/status.pythonService` 已有字段，补 `agent` 子状态。

### P2.7 验收与文档（S）

- 对照 `DEVELOPMENT_PLAN.md` P2 验收清单逐条过（白名单、不写库、不覆盖手工、标注时间、不影响告警）。
- 更新 `API.md §10`、`DATABASE.md`（V8）、`UI.md`（AI 复盘页）、README（AI 配置说明）。

## 3. 与现有文档的一处差异（需同步）

`DEVELOPMENT_PLAN.md` P2 第 4 条写的是「保存为 `MANUAL` 日记」，而 `API.md §7` 与数据库状态机都是 `market_recap` 的 `AI_DRAFT → MANUAL`。本计划以 **API.md 的市场复盘方案**为准（理由见 D2），并已把 DEVELOPMENT_PLAN 该条改为「市场复盘草稿」。

## 4. 主要风险与对策

| 风险 | 对策 |
|---|---|
| 模型编造行情数字 | 工具数据是唯一事实源；prompt 显式禁止推测；`limitations` 必填；前端展示数据时间 |
| Token 成本失控 | 只传聚合数据与前 N 条明细；单次请求设 max_tokens；日调用量写日志便于观察 |
| key 泄漏 | 只走 `.env`；日志与接口都不回显；`/agent/v1/health` 只报 `modelConfigured` |
| 模型不支持 tool calling | D5 降级路径：一次性取全量数据 → 结构化填充，功能不中断 |
| LangChain 版本漂移 | 锁小版本；工具封装层做薄，模型 SDK 只在一处使用 |
| 时区/日期错位 | 全链路统一 `Asia/Shanghai`，日期参数显式传，不依赖模型推断「今天」 |
| 生成内容写进正式复盘 | 状态机 + 唯一日期约束 + 「不覆盖 MANUAL」单测三重保护 |

## 5. 明确不做（P2 边界）

- 不做自动交易建议、不给买卖点、不预测点位。
- 不做多轮对话与长期会话记忆（P2.3 之后再评估）。
- 不做 RAG / 向量库 / 新闻抓取（属 P3）。
- 不做定时自动生成复盘（先手动触发，稳定后再加 16:00 定时草稿）。
- AI 不写交易流水、不改告警规则、不发通知。

## 6. 建议执行顺序

```
P2.0 骨架（0.5 天）
  → P2.1 Java 工具 API（1~1.5 天，不依赖模型选型，可先做）
    → P2.2 Python 工具封装（1 天）
      → P2.3 草稿生成（1.5 天，含 prompt 调优）
        → P2.4 落库与确认（1 天）
          → P2.5 前端（1 天）
            → P2.6/2.7 降级与验收（0.5~1 天）
```

**先做 P2.1**：它不依赖模型、不花 token、纯 Java，做完后无论最后选哪家模型都能直接接。
