# StockCat P2 开发计划（AI 每日复盘 Agent）

- 文档版本：V1.0-Draft（2026-10-27）
- 目标：把 AI 接进「每日复盘」这一条闭环，产出**草稿**，用户确认后才成为正式复盘。
- 原则：AI 只读、只写草稿；Python 不连数据库；模型不可用不影响 Java 采集与告警。

## 0. 实施记录（2026-10-27，第一批已落地）

用户确认先做两件事：**首页 AI 助手入口卡 + AI 助手主页面**。据此把原计划里的 P2.0/P2.1/P2.2 以"能跑通的最小闭环"先实现了：

| 计划项 | 落地形态 | 与计划的差异 |
|---|---|---|
| P2.0 骨架 | `app/agent/` + `GET /agent/v1/health` + `STOCKCAT_LLM_*` 配置 | **不引 LangChain**：用 httpx 直连 OpenAI 兼容接口，少一个重依赖、少一处版本漂移；换 LangChain 只改 `llm.py` 一层 |
| P2.1 Java 工具 API | 6 个接口全实现，含只读反射锁测试 | 与计划一致 |
| P2.2 Python 工具封装 | `app/agent/java_tools.py` 白名单 + 并行调用 | 未用 LangChain `StructuredTool`；白名单硬编码效果相同（改代码才能加工具） |
| P2.3 草稿生成 | **先做"问答"而不是"复盘草稿"**：`POST /agent/v1/assistant/ask` | 计划的核心是每日复盘草稿；按本轮需求先验证"工具调用 → 可追溯回答"这条更基础的能力，复盘草稿（P2.4 落库）留到下一批 |
| P2.5 前端 | `/assistant` 页面 + 首页入口卡 + 侧边栏入口 | 计划里没写这个页面；按用户本轮设计新增 |
| 会话存储 | 浏览器 localStorage，**不建表** | 计划默认无状态；P0 明确不做多轮记忆，本地存会话只为"最近对话"可用 |

后续：P2.5（含 AI 配置页）、P2.6 于第四批完成（§0.4），P2.7 验收对照见 §0.5。**P2 计划内事项已全部落地。**

## 0.2 第二批：告警历史 AI 解释（2026-10-27 已落地）

对应原始优先级表中的「P1 档 · AI 告警解释」。

**数据链路**：前端 → Java `POST /api/v1/alert-events/{id}/explain` → Python `POST /agent/v1/alerts/explain`。
Java 负责组装「事件快照 + 当前状态」，Python 只解释、**不做任何指标计算**——这样就不存在
"用今天的 K 线重算当年指标"的风险。

**四段输出**：为什么触发 / 这意味着什么 / 当前状态 / 数据依据（另附数据限制），前端以右侧抽屉呈现，不离开列表。

**关键设计（与计划的差异）**：

| 项 | 计划 | 实现 | 原因 |
|---|---|---|---|
| 解释范围 | 只读事件快照 | 快照 + **当前状态** | 原型第 3 段问"当前状态如何"，不查行情答不了；行情类规则只查行情、指标类才查 250 根 K 线 |
| 模型参与度 | 未细化 | 模型只能改写 `why`/`meaning`；`current`/`evidence`/`limits` 恒由模板生成 | 数据依据一旦能被模型"润色"，就无法保证与库里的值一致 |
| 快照缺指标 | 未细化 | `snapshotComplete=false`，**连 `why` 也不交模型** | 模型在这种场景最容易补一个"看起来合理"的数字 |
| 落库 | 不涉及 | 不写任何表 | 解释是派生视图，可以随时重算，没有保存价值 |

**落地文件**：`backend-python/app/agent/alert_explain.py`（规则语义库 + 四段生成）、`alert_service.py`（编排）、
`backend-java/.../service/AlertExplainService.java`、`frontend/src/views/AlertHistoryView.vue`（抽屉）。

**待补**：`PREMIUM_*` 事件目前不返回"当前溢价率"（要另查 IOPV/净值），`limits` 里已如实说明"本次未做溢价对比"。

## 0.3 第三批：AI 每日复盘草稿（2026-10-27 已落地）

对应 P2.3（生成）+ P2.4（落库与确认）+ P2.5 的复盘页部分。**闭环已通**：
生成（不落库）→ 弹窗内可编辑 → 保存为 `AI_DRAFT` → 用户编辑保存 → `MANUAL`。

| 项 | 计划 | 实现 | 原因 |
|---|---|---|---|
| 取数 | 模型选工具（D5） | **固定 5 个工具**（market-brief / positions / watchlist-quotes / alert-events / recaps） | 写一篇复盘需要的上下文是确定的，让模型"决定查什么"只会漏查和慢；问答场景才需要 planner |
| 无数据 | 返回 `NO_DATA` | `502 NO_DATA`：指数、自选、持仓全空即拒绝生成 | "今天市场平淡"这种空话是最难被发现的编造 |
| 板块表现 | 未细化 | 只按自选分组汇总，并**恒带**"非行业板块"限定 | 本项目没有采集行业板块/资金流，写出来就是编的 |
| 模型参与度 | 全文交模型 | 模型只写四段正文；`limitations` 恒由模板生成 | 限制项被"润色"掉比编数字更难发现 |
| 模型失败 | 未细化 | 四段缺任一段 → 整篇退回规则底稿（`mode=rule_based`）并给出 `degraded` | 不拼半截草稿 |
| 落库 | 新建 `ai_recap` 表 | **并入 `market_recap`**，V8 加 `ai_generated_at / ai_model / ai_data_as_of / ai_limitations` | 一天一篇的唯一约束已经把草稿与正文限定为同一条记录的两个状态，跨表就要维护一致性 |
| 确认语义 | PATCH 转 MANUAL | 保留 `ai_*` 溯源字段 | 确认的是"内容"，不是"来源"；几周后回看要知道数据截至何时 |
| 并发 | 未提 | `updateDraft` 带 `WHERE status='AI_DRAFT'`，影响 0 行即 409 | 生成草稿与用户确认可能同时发生，不能靠"先查后写" |

**落地文件**：`backend-python/app/agent/{formatting,recap_draft,recap_service}.py`、`llm.draft_recap`、
`backend-java/.../service/RecapDraftService.java`、`V8__recap_ai_source.sql`、
`frontend/src/views/MarketRecapView.vue`（生成按钮 + 草稿弹窗 + 确认提示条）。

**顺带做的两件小事**：
- 数值格式化统一抽到 `app/agent/formatting.py`，问答与草稿共用一份——同一个数字两处写法不一致，
  比没格式化更糟；
- 只有一个有效自选时不写"领涨/领跌"（那会是同一只，属于噪声）。

**验证**：Python 90 个测试、Java 167 个测试、`vue-tsc` + `vite build` 全过；8082/8101 实例上跑通
"生成 → 保存 → 确认 → 再次生成 409 → 删除"整条链路。

## 0.4 第四批：可观测与 AI 配置页（2026-10-27 已落地，P2.5/P2.6 收尾）

补的是"出问题时能不能查"和"用户怎么知道 AI 现在是什么状态"，功能本身没有新增。

| 项 | 计划 | 实现 | 原因 |
|---|---|---|---|
| 运行日志 | 记 date/model/耗时/工具/token/错误码 | `app/agent/audit.py` 统一出口，三场景各一行 `key=value` | 三处各写一份 `logger.info` 必然字段漂移，按 scene grep 也会失效 |
| 日志内容 | 不记 key、不记 prompt | 函数签名里根本没有这些参数；`errText` 压成一行并截断 120 字 | 换行会把一条日志撑成十几行；截断顺带避免上游 HTML 灌进日志 |
| 失败留痕 | 未细化 | **拒绝生成**（`NO_DATA`）与模型失败都写一条 | 只在成功时记日志，用户说"点了没反应"时什么都查不到 |
| Python 日志级别 | 未提 | `main.py` 里 `basicConfig(INFO)` | uvicorn 只配置 `uvicorn.*` 的 logger，不配 root 的话 INFO 全被丢掉（只剩 lastResort 的 WARNING） |
| `/system/status` | 补 `agent` 子状态 | `pythonService{status,baseUrl,agent{status,modelConfigured,model,toolCount},error}`，DOWN 时不带 agent | 只探一次健康检查，不搬整个工具白名单——系统状态要的是"几个能力被打开" |
| 探活失败 | 未提 | 捕异常、返回 `DOWN + error`，接口仍 200 | AI 没开是正常状态，不该让"系统状态"看起来像整个应用坏了 |
| AI 配置页 | 显示模型名/配置状态 | 另加工具 chip、三场景降级表、环境变量指引、边界说明 | 用户真正会问的是"为什么这次没有分析"，答案应该在页面上而不是文档里 |
| 页面填 key | 未提 | **故意不做** | 能在浏览器里填的 key，必然会被存到浏览器或随前端代码带出去 |

**落地文件**：`backend-python/app/agent/audit.py`、`tests/test_agent_audit.py`、
`AssistantClient.pythonService()`、`SystemController`（+`SystemControllerTest`）、
`frontend/src/views/AiConfigView.vue`（路由 `/settings/ai`，侧边栏去掉 P2 占位）。

**验证**：Python 96 个测试（+6）、Java 173 个测试（+6）、`vue-tsc` + `vite build` 全过；
8082/8101 实例上 `system/status` Python 在线时 `pythonService.status=UP, toolCount=6`，
停掉 Python 后变 `DOWN` 且 `application` 仍为 `UP`；问答落了一条
`scene=assistant mode=rule_based qChars=9 elapsedMs=149 tools=positions:ok usage=- err=-`。

## 0.5 P2.7 验收对照（2026-10-27）

逐条对照 `DEVELOPMENT_PLAN.md` §P2 的五条验收：

| 验收项 | 结论 | 证据 | 已知差距 |
|---|---|---|---|
| Agent 只能调用白名单工具 | ✅ | `app/agent/java_tools.py` 白名单硬编码；`AgentToolControllerTest` 用反射断言该控制器只有 GET 映射 | 加工具要同时改两侧代码，这是刻意的摩擦 |
| 不直接访问业务库、不改规则/流水/通知 | ✅ | Python 进程**没有数据库依赖**（无驱动、无连接串）；工具层只有 6 个 GET；复盘落库在 Java `RecapDraftService` | — |
| 未确认的草稿不进入正式内容 | ✅ | `POST /recaps/drafts` 只写 `AI_DRAFT`；`PATCH` 保存才转 `MANUAL`；`recaps` 工具只返回 `MANUAL`；同日已有 `MANUAL` 时生成直接 `409` | 复盘**列表页**会显示草稿（带琥珀色徽标），这是给用户看和确认用的；作为 AI 取数依据时被排除 |
| 返回结果标注数据时间；工具失败不编造 | ✅ | 三类响应都带 `dataAsOf` / `limits`；工具失败进 `missing` 与「执行过程」；无任何数据时 `502 NO_DATA` 而不是写空话 | 采集时点快照不是交易所收盘价，已在 `limits` 中固定说明 |
| Python / 模型不可用不影响 Java 告警 | ✅ | 独立进程 + 5s 工具超时 + 60s 整体超时；`DATA_SOURCE_UNAVAILABLE` 只落在 AI 接口；实测停掉 Python 后 `system/status` 仍是 `application=UP` | 采集与告警不经过 AI，这一点由架构保证而非配置 |

**P2 整体收尾时仍留着的口子（不是本次遗漏，是明确不做或待评估）**：

- 没有行业板块与资金流数据：`sectorNotes` 只能按自选分组汇总，且恒带「非行业板块」限定；
- `PREMIUM_*` 告警解释不返回"当前溢价率"（需另查 IOPV/净值），`limits` 里已如实说明；
- 不做多轮对话与长期记忆、不做定时自动生成复盘（先手动触发，稳定后再评估 16:00 定时草稿）；
- 前端三个 AI 入口（助手页、告警抽屉、复盘草稿弹窗）本轮只做了类型检查、构建与接口联调，
  浏览器里的实际点击与视觉细节仍需人工过一遍。

## 0.1 现状盘点（写计划前实际核对过）
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

### P2.1 Java 受控只读工具 API（M）✅ 已完成（2026-10-27）

新增 `web/AgentToolController`（`service/AgentToolService` 聚合，`web/dto/AgentToolDtos` 载荷），
统一前缀 `/api/v1/agent/tools`，**全部 GET、全部只读、无任何写接口**（`AgentToolControllerTest` 用反射锁死）：

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

**落地结果**：6 个工具全部实现，17 个单测（`AgentToolServiceTest` 14 + `AgentToolControllerTest` 3），
Java 侧 146 个测试全过；真实接口在 8082 验证实例上逐个调通。实现中确定的三条细节：

- `positions` 的 `priceComplete=false` 语义与 `PortfolioService` 保持一致：缺价持仓不计入市值汇总，只进 `missing`；
- `recaps` **只返回 `status=MANUAL`**——AI 草稿不能当作历史依据再喂回模型（否则自我强化），排除条数写进 `missing`；
- `market-brief` 的涨跌统计只认 `VALID` 行情，`dataAsOf` 在指数缺失时退化为自选行情采集时间。

### P2.2 Python Agent 工具封装（M）

- 新增 `app/agent/tools.py`：把 P2.1 的接口包成 LangChain `StructuredTool`（名称、描述、参数 schema 手写，**不靠模型猜**）。
- `app/agent/client.py`：httpx 调 Java，超时/重试（GET 可重试 1 次），错误映射 `JAVA_UNAVAILABLE`。
- 白名单在代码里硬编码，新增工具必须改代码→天然「只能调用白名单工具」。

验收：mock transport 下单测工具调用与错误映射；Python 不需要连 PG。

### P2.3 复盘草稿生成（M）— P2 的核心 ✅ 已完成（2026-10-27，见 §0.3）

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

### P2.4 草稿落库与确认闭环（M）✅ 已完成（2026-10-27，见 §0.3）

- `POST /api/v1/recaps/drafts`：写入 `status=AI_DRAFT`，同一天已有 `MANUAL` → `409`（body 说明「今天已写过正式复盘」）；已有 `AI_DRAFT` → 覆盖草稿。
- 现有 `PATCH /recaps/{id}` 沿用：编辑 `AI_DRAFT` 保存后转 `MANUAL`（API.md §7 已有此约定，补齐实现与测试）。
- V8 迁移（推荐）：`market_recap` 加 `ai_generated_at`、`ai_model`、`ai_data_as_of`、`ai_limitations JSONB`——「生成时间与数据范围」要落库，不能只存在返回体里。

验收：AI_DRAFT 不出现在「正式复盘」列表筛选里（或明确标记）；PATCH 后状态流转正确；MANUAL 不可被 AI 覆盖（单测断言）。

### P2.5 前端 AI 复盘页（M）✅ 已完成（2026-10-27，复盘页见 §0.3、配置页见 §0.4）

- 市场复盘页加「✨ AI 生成草稿」按钮；三态：生成中（含耗时提示）/ 失败（显示错误码与可重试）/ 成功。
- 草稿确认页：分区编辑 `marketSummary` / `sectorNotes` / `content`；顶部固定展示「生成时间 / 数据截至 / 数据限制」，不可编辑、不可省略。
- 当天已有 MANUAL 时按钮置灰并说明原因（「今天已写过正式复盘，AI 草稿不会覆盖」）。
- 侧边栏「AI 配置」占位页改为：显示模型名/配置状态（只读，key 只显示是否已配置）。

验收：断网/停 Python 时按钮报「AI 服务不可用」而页面其余部分正常；草稿未确认不入正式列表。

### P2.6 降级、超时与可观测（S）✅ 已完成（2026-10-27，见 §0.4）

- 超时：整体 60s 上限，Java 工具单次 5s；**生成失败不自动重试**（避免双计费），由用户点重试。
- 日志：记 `date / model / 耗时 / 工具调用列表 / token 用量 / 错误码`，**不记 key、不记完整 prompt 原文**。
- Python 不可用：Java 侧 `/system/status.pythonService` 已有字段，补 `agent` 子状态。

### P2.7 验收与文档（S）✅ 已完成（2026-10-27，验收对照见 §0.5）

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
