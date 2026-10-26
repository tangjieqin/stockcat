# StockCat API 接口契约

- 文档版本：V1.0-Draft
- 核心 API：Java Spring Boot，统一前缀 `/api/v1`
- 格式：JSON，UTF-8
- 时间：ISO-8601 时间戳，建议返回带 `Z` 的 UTC 时间。
- 百分比：统一使用百分数值，例如 `2.5` 表示 `2.5%`。
- 本文档是契约草案；实现与前端必须保持同步。

## 1. 通用响应与错误

成功响应直接返回资源或分页对象，不额外包一层 `data`，避免重复包装。错误统一为：

```json
{
  "timestamp": "2026-10-24T01:00:00Z",
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "请求参数不合法",
  "details": [
    {"field": "symbol", "reason": "不能为空"}
  ],
  "path": "/api/v1/instruments"
}
```

建议错误码：

- `VALIDATION_ERROR`：参数校验失败
- `NOT_FOUND`：资源不存在
- `CONFLICT`：重复添加或状态冲突
- `DATA_SOURCE_UNAVAILABLE`：外部数据源不可用
- `STALE_DATA`：数据过期（仅在业务必须依赖新数据时使用）
- `INTERNAL_ERROR`：未预期错误

HTTP 状态码：200 查询/更新成功；201 创建成功；202 仅用于异步任务确实已接受；204 删除成功；400 参数错误；404 不存在；409 冲突；502/503 外部依赖不可用。不要对所有请求固定返回 200。

## 2. 标的与自选

### `GET /instruments`

查询标的，可选参数：`market`, `type`, `keyword`, `page`, `size`。

响应示例：

```json
{
  "items": [
    {"id": 1, "market": "CN_A", "symbol": "600000", "name": "示例标的", "type": "STOCK", "currency": "CNY"}
  ],
  "page": 0,
  "size": 20,
  "total": 1
}
```

示例名称和代码仅为契约示例，不代表真实行情数据。

### `POST /watchlist`

请求（`name` 可选：P0.3 起服务端从行情源自动验证并补全名称；行情源不可用且未提供名称时返回错误，不创建无法验证的标的）：

```json
{"market": "CN_A", "symbol": "600000", "type": "STOCK"}
```

响应 `201 Created`：

```json
{"id": 1, "instrumentId": 1, "market": "CN_A", "symbol": "600000", "name": "示例标的", "createdAt": "2026-10-24T01:00:00Z"}
```

重复添加返回 `409 CONFLICT`。如果服务端无法验证或补全标的主数据，应返回明确错误，不默默创建伪造名称。

### `GET /watchlist`

返回自选标的及最新可用快照：

```json
{
  "items": [
    {
      "instrument": {"id": 1, "market": "CN_A", "symbol": "600000", "name": "示例标的", "type": "STOCK"},
      "quote": {
        "lastPrice": 10.25,
        "previousClose": 10.00,
        "changePercent": 2.5,
        "marketTime": "2026-10-24T01:00:00Z",
        "collectedAt": "2026-10-24T01:00:02Z",
        "dataStatus": "VALID",
        "source": "eastmoney-push2"
      }
    }
  ]
}
```

如果没有快照，`quote` 为 `null`，不能将价格设为 0。

### `DELETE /watchlist/{instrumentId}`

成功返回 `204 No Content`。只移除自选关系，不删除标的和历史数据。

### 监控标的（告警引擎的监控对象，与自选相互独立）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/monitor-targets` | 列出监控标的及最新行情状态 |
| POST | `/monitor-targets` | 请求 `{"market":"CN_A","symbol":"600000","type":"STOCK","name":"示例标的"}`，instrument 不存在时自动创建，返回 `201`。P0.3 起名称从行情源自动补全（`name` 仅在行情源不可用时作为显式后备，两者皆缺则返回错误） |
| PATCH | `/monitor-targets/{id}` | 启用/停用：`{"enabled":false}` |
| DELETE | `/monitor-targets/{id}` | 移除监控关系，`204` |

首页指数行情（上证指数等）由内置配置驱动，不通过本接口管理。

## 3. 行情

### `GET /quotes/latest`

参数：`instrumentIds`（可重复参数或逗号分隔，最终实现选一种并固定）、可选 `includeStale=true`。  
返回最新快照集合。必须包含 `marketTime`、`collectedAt`、`dataStatus`、`source`。

### `POST /quotes/refresh`

请求：

```json
{"instrumentIds": [1, 2]}
```

用于请求一次受控刷新。P0 可同步返回刷新汇总；如果未来改为后台任务，再采用 `202 Accepted` 并返回 task ID。  
响应示例：

```json
{"requested": 2, "succeeded": 1, "failed": 1, "results": [{"instrumentId": 1, "status": "SUCCESS"}, {"instrumentId": 2, "status": "FAILED", "code": "DATA_SOURCE_UNAVAILABLE"}]}
```

不保证供应商实时可用；前端需显示部分成功状态。

## 4. 告警规则

### `GET /alert-rules`

可选参数：`instrumentId`, `enabled`, `page`, `size`。

### `POST /alert-rules`

请求：

规则类型可插拔：`ruleType` 是开放枚举，`params` 结构由类型决定，服务端按类型做参数校验，并校验该类型所需数据已接入（如溢价率规则要求 IOPV 可用，P0 未接入时返回 `400 VALIDATION_ERROR` 并说明原因）。

```json
{
  "name": "示例价格告警",
  "instrumentId": 1,
  "ruleType": "PRICE_ABOVE",
  "params": {"threshold": 12.50},
  "cooldownMinutes": 30,
  "enabled": true
}
```

P0 支持的 `ruleType` 与 `params`：

| ruleType | params | 说明 |
|---|---|---|
| `PRICE_ABOVE` / `PRICE_BELOW` | `{"threshold": 10.5}` | 最新价 ≥/≤ 阈值（含边界） |
| `CHANGE_PERCENT_ABOVE` / `CHANGE_PERCENT_BELOW` | `{"threshold": -20}` | 涨跌幅阈值，百分数值，跌 20% 填 -20 |

P1 计划：`PREMIUM_ABOVE`/`PREMIUM_BELOW`（溢价率）、`INDICATOR_CROSS`（指标交叉，params 含指标与周期参数）。

批量创建：`POST /alert-rules/batch`，请求 `{"instrumentIds":[3,4,5], "ruleType":"CHANGE_PERCENT_BELOW", "params":{"threshold":-20}, ...}`，一次为多个监控标的创建相同类型与参数的规则；返回逐条成功/失败结果，部分失败不影响其他标的。

响应 `201 Created`，返回规则字段及 `createdAt`。

### `PATCH /alert-rules/{id}`

允许更新 `name`、`params`、`cooldownMinutes`、`enabled`。不允许用 PATCH 悄悄更换标的或 `ruleType`；需要换类型时应删除重建，历史事件快照不受影响。

### `DELETE /alert-rules/{id}`

返回 `204 No Content`。历史事件保留快照。

## 5. 告警事件与通知

### `GET /alert-events`

参数：`from`, `to`, `instrumentId`, `ruleId`, `notificationStatus`, `page`, `size`。默认按 `triggeredAt` 倒序。

响应：

```json
{
  "items": [
    {
      "id": 100,
      "instrumentId": 1,
      "symbol": "600000",
      "ruleName": "示例价格告警",
      "ruleType": "PRICE_ABOVE",
      "paramsSnapshot": {"threshold": 12.5},
      "observedPrice": 12.6,
      "observedChangePercent": 1.2,
      "observedDetail": null,
      "quoteMarketTime": "2026-10-24T01:00:00Z",
      "triggeredAt": "2026-10-24T01:00:02Z",
      "notifications": [{"id": 500, "channel": "EMAIL", "status": "SENT", "sentAt": "2026-10-24T01:00:03Z"}]
    }
  ],
  "page": 0,
  "size": 20,
  "total": 1
}
```

### `GET /alert-events/{id}`

返回单条事件和完整通知记录。

### `POST /notifications/{id}/retry`

重试失败通知；只允许对 `FAILED` 记录重试，返回更新后的通知记录。必须避免重复发送已成功通知。

## 6. 交易流水与持仓

### `GET /trades`
参数：`instrumentId`, `direction`, `from`, `to`, `page`, `size`。按 `tradeTime` 倒序。

### `POST /trades`
请求：
```json
{"instrumentId": 1, "direction": "BUY", "price": 10.20, "quantity": 1000, "fee": 5.00, "tradeTime": "2026-10-24T09:35:00+08:00", "note": "突破买入"}
```
响应 `201 Created`。校验：价格 > 0、数量 > 0、`SELL` 数量不得超过当时持有量（违反返回 `409 CONFLICT`，message 说明当前可卖数量）。

### `DELETE /trades/{id}`
`204`。删除后重新计算受影响标的的持仓；若删除导致某笔历史卖出在重放时出现超卖，返回 `409` 并拒绝。

### `GET /positions`
返回全部当前持仓：
```json
{"items": [
  {"instrumentId": 1, "symbol": "600000", "name": "示例标的",
   "quantity": 1000, "avgCost": 10.205, "lastPrice": 10.25,
   "marketValue": 10250.00, "unrealizedPnl": 45.00,
   "realizedPnl": 120.00, "priceAsOf": "2026-10-24T15:00:00Z", "priceStatus": "VALID"}
]}
```
快照缺失或过期时 `lastPrice/marketValue/unrealizedPnl` 为 `null` 并标注 `priceStatus`，不得按 0 计算。

## 7. 复盘与我的日记

### 市场复盘 `/recaps`（按交易日，一天一篇）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/recaps` | 参数：`date`、`from`/`to`、`keyword`、分页；按 `recapDate` 倒序 |
| POST | `/recaps` | 创建：`{"recapDate":"2026-10-24","title":"...","marketSummary":"...","sectorNotes":"...","content":"...","relatedAlertEventIds":[100]}`；`recapDate` 已存在返回 `409` |
| GET | `/recaps/{id}` | 单条详情 |
| PATCH | `/recaps/{id}` | 修改各字段；编辑 `AI_DRAFT` 并保存后状态变 `MANUAL`（用户确认） |
| DELETE | `/recaps/{id}` | `204`，前端需二次确认 |

`marketSummary`（市场主要情况）与 `sectorNotes`（板块表现）为可选结构化字段，编辑页分区填写；用户手工创建的复盘 `status` 恒为 `MANUAL`，P2 的 AI 只能生成 `AI_DRAFT`，不得覆盖 `MANUAL`。

### 我的日记 `/diary`（树状长期笔记本）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/diary/tree` | 返回整棵树（id、parentId、nodeType、title、sortOrder、含子节点）；个人规模一次全量返回 |
| GET | `/diary/nodes/{id}` | 单节点详情（DOC 含 content） |
| POST | `/diary/nodes` | 创建：`{"parentId":5或null,"nodeType":"FOLDER"/"DOC","title":"...","content":"..."(仅DOC)}`；超深度限制返回 `400` |
| PATCH | `/diary/nodes/{id}` | 修改 `title`/`content`/`relatedInstrumentIds`；**移动**用 `{"parentId":X,"sortOrder":N}`，服务端做成环与深度校验，违规返回 `400` |
| DELETE | `/diary/nodes/{id}?confirm=true` | DOC 直接删除；FOLDER 级联删除整棵子树，**必须**带 `confirm=true` 且前端强确认显示将删除的文档数，否则返回 `400` |

搜索：`GET /diary/nodes?keyword=...` 对 DOC 的标题与正文做 `ILIKE` 匹配，返回命中文档列表（含其树路径）。

## 8. 首页总览

### `GET /dashboard`
一次返回首页聚合数据（P0 简单串行聚合即可，不做专门优化）：
```json
{
  "indices": [{"symbol": "000001", "name": "上证指数", "lastPrice": 3245.16, "changePercent": 0.42, "marketTime": "...", "dataStatus": "VALID"}],
  "positionsSummary": {"totalMarketValue": 10250.00, "totalUnrealizedPnl": 45.00, "positionCount": 1},
  "topMovers": [{"instrumentId": 1, "symbol": "600000", "changePercent": 2.5}],
  "pendingAlerts": [{"eventId": 100, "symbol": "600000", "ruleName": "...", "triggeredAt": "..."}],
  "latestRecap": {"diaryId": 7, "entryDate": "2026-10-24", "title": "...", "status": "MANUAL"}
}
```
监控的指数标的在配置中维护（如 `000001` 上证、`399001` 深成、`399006` 创业板）；任一数据源失败时对应块返回 `null` 并附错误码，不影响其他块。

## 9. 系统状态

### `GET /system/health`

返回进程级健康状态。

### `GET /system/status`

返回业务组件状态，例如：

```json
{
  "application": "UP",
  "database": "UP",
  "quoteCollector": {"status": "DEGRADED", "lastSuccessAt": "2026-10-24T01:00:00Z", "lastErrorCode": "DATA_SOURCE_UNAVAILABLE"},
  "notification": {"emailConfigured": true, "pushChannelConfigured": false},
  "pythonService": {"status": "UNKNOWN"}
}
```

不要在该接口返回密码、token、环境变量值或堆栈。

## 10. Python API（分阶段）

### P0

- `GET /health`：Python 进程健康检查；不代表数据源或 Agent 已可用。

### P1 数据适配器（内部契约草案）

- `GET /internal/v1/market-data/klines?market=CN_A&symbol=...&interval=1d&from=...&to=...`
- `GET /internal/v1/funds/{symbol}/reference-data`  
  返回值需包含 `source`, `marketTime`/`asOf`, `collectedAt`, `dataStatus`。Java 校验后持久化。只有数据源验证通过后才实现具体字段。

### P2 Agent

- `POST /agent/v1/chat`  
  请求：

```json
{"message": "请总结今天自选标的的表现", "conversationId": "optional-client-id"}
```

响应：

```json
{
  "answer": "基于当前可用数据生成的摘要……",
  "generatedAt": "2026-10-24T01:00:00Z",
  "dataAsOf": "2026-10-24T00:55:00Z",
  "limitations": ["部分标的数据可能延迟"]
}
```

Agent 通过 Java 受控只读工具查询数据，不直接访问数据库。以上是未来契约，不表示 P0 已实现。

## 11. 兼容性与版本策略

- P0 API 前缀固定为 `/api/v1`。
- 字段改名、语义变化或删除属于破坏性变更，需要更新文档、前端类型和测试。
- 新增可选字段优先保持向后兼容。
- 每个接口至少有一个成功测试和关键错误测试。
