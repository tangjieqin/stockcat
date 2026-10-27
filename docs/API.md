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

### `GET /instruments/{id}`

单个标的主数据（P1.7 详情页按 id 直开用，不再靠列表搜索猜）。不存在返回 `404 NOT_FOUND`（`标的不存在: id=…`）。

```json
{"id": 1, "market": "CN_A", "symbol": "600519", "name": "贵州茅台", "type": "STOCK", "currency": "CNY"}
```


### `POST /watchlist`

请求（`name` 可选：P0.3 起服务端从行情源自动验证并补全名称；行情源不可用且未提供名称时返回错误，不创建无法验证的标的。`groupName` 可选：自定义分组，空白/null = 默认「自选」，最长 64 字符）：

```json
{"market": "CN_A", "symbol": "600000", "type": "STOCK", "groupName": "我的组合1"}
```

响应 `201 Created`：

```json
{"id": 1, "instrumentId": 1, "market": "CN_A", "symbol": "600000", "name": "示例标的", "groupName": "我的组合1", "createdAt": "2026-10-24T01:00:00Z"}
```

重复添加返回 `409 CONFLICT`。如果服务端无法验证或补全标的主数据，应返回明确错误，不默默创建伪造名称。

### `GET /watchlist`

返回自选标的及最新可用快照。`groupName` 为 `null` 表示默认「自选」；**一个标的只属于一个分组**（同一标的在不同分组中重复出现是不支持的）：

```json
{
  "items": [
    {
      "instrument": {"id": 1, "market": "CN_A", "symbol": "600000", "name": "示例标的", "type": "STOCK"},
      "groupName": null,
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

### `GET /watchlist/groups`

返回自定义分组名列表（不含默认「自选」）。分组不需要单独创建/删除：随第一个成员加入而出现，最后一个成员移出后自然消失。

```json
["我的组合1", "我的组合2"]
```

### `PATCH /watchlist/{instrumentId}/group`

移动标的到分组；`groupName` 传 `null`/空白 = 移回默认「自选」。条目不存在返回 `404`。

```json
{"groupName": "我的组合2"}
```

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

### 历史日 K（P1，已实现）

日 K 由 Python 适配器抓取、Java 校验后落库（原则：抓取在 Python，业务判定在 Java）。

#### `GET /instruments/{id}/klines`

| 参数 | 默认 | 说明 |
|---|---|---|
| `adjust` | `qfq` | `qfq` 前复权 / `hfq` 后复权 / `raw` 不复权；其他值 `400 VALIDATION_ERROR` |
| `limit` | 120 | 1~500。上限 500 是因为前复权长窗口会把早期价格算成负数 |
| `end` | — | 截止交易日（含），`YYYY-MM-DD` |

返回按 `date` **升序**排列的已落库数据：

```json
{"instrumentId": 1, "symbol": "600519", "adjust": "qfq", "count": 2,
 "sourceCode": "eastmoney-push2his", "lastFetchedAt": "2026-10-26T11:38:31Z",
 "bars": [{"date": "2026-10-26", "open": 1325.4, "high": 1350.0, "low": 1324.79, "close": 1349.98,
           "volume": 41599.0, "amount": 5574287630.0, "changePercent": 1.12, "turnoverRate": 0.33}]}
```

- **`count = 0` 表示尚未同步**，不是"该标的没有行情"。前端应提示先同步，不能画成一条水平假线。
- `volume` 单位为手，`amount` 单位为元；价格是复权后口径。

#### `POST /instruments/{id}/history/sync`

参数 `days`（默认 120，1~500）、`adjust`（默认 qfq）。幂等（UPSERT），可反复调用。返回：

```json
{"instrumentId": 1, "symbol": "600519", "adjust": "qfq", "status": "SUCCESS", "code": null, "message": null,
 "requestedLimit": 8, "accepted": 8, "rejectedUnclosed": 0, "rejectedInvalid": 0, "skippedUpstream": 0,
 "source": "eastmoney-push2his", "elapsedMs": 804}
```

- `status`：`SUCCESS` 有数据落库 / `FAILED` 未取到可用数据 / `SKIPPED` 代码规则不覆盖（如北交所），未请求上游。
- `rejectedUnclosed`：上游返回了尚未收盘的当日 K 线，被拒写；`rejectedInvalid`：负价等脏行被拒写。
  这两个数必须暴露给前端/日志，不能只看 `accepted` 就认定全部成功。
- 失败码：`NO_DATA` / `DATA_SOURCE_UNAVAILABLE` / `UPSTREAM_TIMEOUT` / `UPSTREAM_ERROR` / `NOT_SUPPORTED` / `UNSUPPORTED_SYMBOL` / `SYNC_ERROR`。
- 上游（Python）未启动时返回 `FAILED` + `DATA_SOURCE_UNAVAILABLE`，**不影响**实时行情与告警。

#### `POST /history/sync`

参数 `days`。对"自选 ∪ 启用中的监控标的 ∪ 当前持仓"批量同步，返回
`{instruments, succeeded, failed, skipped, elapsedMs, results[]}`，用于首次建仓或历史缺口修复。

### 技术指标（P1，已实现）

指标**不落库**，按查询现算（决策 D2）：避免"K 线被修正 → 指标失效"的一致性负担。
全部口径对齐通达信：MACD 柱 = 2×(DIF−DEA)、RSI 用 SMA(X,N,1) 递推、KDJ 的 K/D 用 SMA 递推且 J = 3K−2D。

#### `GET /instruments/{id}/indicators`

| 参数 | 默认 | 说明 |
|---|---|---|
| `adjust` | `qfq` | 与 K 线一致 |
| `limit` | 250 | 参与计算的 K 线根数，1~500 |

```json
{"instrumentId": 1, "symbol": "600519", "adjust": "qfq", "count": 120,
 "requiredBars": {"macd": 34, "rsi6": 7, "rsi12": 13, "rsi24": 25, "kdj": 9},
 "points": [{"date": "2026-10-26", "close": 1349.98, "dif": 15.151410, "dea": 7.141003,
             "macdHist": 16.020815, "rsi6": 75.4697, "rsi12": 68.1405, "rsi24": 60.1525,
             "k": 84.2160, "d": 79.4304, "j": 93.7871}]}
```

- `points` 与 K 线**逐根对齐**（同下标同日期），前端可按 index 直接叠多副图。
- **预热不足的字段为 `null`，不是 0**：0 会被画成贴底的线，看起来像"指标到极值"。
  首根有值的下标：`dif/dea/macdHist` = 33、`rsi6` = 6、`rsi12` = 12、`rsi24` = 24、`k/d/j` = 8。
- `count < requiredBars` 时对应字段整列为 `null`，前端应提示"先同步更多历史"，
  而不是把空图当成"没有指标"。
- `j` 允许 >100 或 <0（`J = 3K−2D` 的正常结果），前端**不要裁剪**，否则和看盘软件对不上。
- 价格连续不变导致 RSI 分母为 0 时返回 `null`（无定义），不返回 0。

### 基金参考数据与溢价（P1.4，已实现）

数据两条路：**IOPV + 价格**由 Java 直连腾讯（`tencent-qt`，随行情每 15 秒刷新）；
**单位净值**由 Java → Python 适配器取天天基金 `f10/lsjz`（`eastmoney-f10-lsjz`，每晚 21:30 + 启动补跑）。
净值必须带 `navDate`——没有日期的净值不展示、不算溢价。

溢价率**不落库**，查询时现算，三条规则（对应决策 D5 与 DATA_SOURCES §3.1）：

1. ETF 有 IOPV → `premiumBasis=IOPV`，`premiumAsOf` 为 IOPV 时间戳；
2. 没有 IOPV（LOF/取数失败）→ 用**带日期**的最近净值，`premiumBasis=NAV`，`premiumAsOf` 为净值日期，
   `navStaleDays` = 净值日期距行情日的自然日数（LOF 盘中必然 ≥1，前端要说明"这是拿 T-x 净值算的"）；
3. 两者都缺 → `premiumRate=null`。**不用 idx77**（上游对 LOF 的基准是 T-1 净值却无标注）。

#### `GET /instruments/{id}/fund-reference?days=30`

| 参数 | 默认 | 说明 |
|---|---|---|
| `days` | 30 | 最近 N 个交易日，1~250 |

```json
{"instrumentId": 2, "symbol": "510300", "type": "ETF", "applicable": true, "reason": null,
 "latest": {"tradeDate": "2026-10-26", "price": 4.179, "iopv": 4.1782, "iopvTime": "2026-10-26T08:14:32Z",
            "nav": 4.1797, "navDate": "2026-10-26", "navStaleDays": 0,
            "premiumRate": 0.0191, "premiumBasis": "IOPV", "premiumAsOf": "2026-10-26T08:14:32Z",
            "priceSource": "tencent-qt", "navSource": "eastmoney-f10-lsjz", "fetchedAt": "..."},
 "history": [ ...同样结构的按天倒序列表 ]}
```

- **股票**返回 `applicable=false` + `reason`（不是 404——"没有这个概念"与"查不到"是两件事）；
- `latest` 为 null + `reason` 说明尚未同步；`history` 每行用**当天自己**的基准算溢价，
  不拿今天的净值去算昨天的；
- 手算核对（2026-10-26 实测）：510300 `(4.179−4.1782)/4.1782×100 = 0.0191` ✅；
  161725（LOF）`(0.544−0.5459)/0.5459×100 = −0.348`、基准 NAV ✅。

#### `GET /fund-references/latest?instrumentIds=1,2`

批量取当前溢价（自选列表用），返回以 instrumentId 为键的 Map；**股票不在结果里**，前端显示 —。

#### `POST /instruments/{id}/fund-reference/refresh`

手动刷新单只基金：腾讯行情 + Python 净值两条都跑。返回：

```json
{"instrumentId": 2, "symbol": "510300", "marketUpdated": true, "navWritten": 30, "navSkipped": 0,
 "status": "SUCCESS", "code": null, "message": null, "elapsedMs": 277}
```

- `status`：SUCCESS 两路都成 / PARTIAL 一路成（如 Python 未启动但 IOPV 成功）/ FAILED 都没成；
- 失败码：`DATA_SOURCE_UNAVAILABLE`（点名 Python 服务）等；
- 调度：净值同步每晚 21:30（`stockcat.fund.nav.cron`，实测 T 日净值当晚才公布）+ 启动补跑（`on-startup: true`）。

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
  "cooldownSeconds": 1800,
  "enabled": true
}
```

支持的 `ruleType` 与 `params`：

| ruleType | params | 评估时机 | 说明 |
|---|---|---|---|
| `PRICE_ABOVE` / `PRICE_BELOW` | `{"threshold": 10.5}` | 盘中每 30 秒 | 最新价 ≥/≤ 阈值（含边界） |
| `CHANGE_PERCENT_ABOVE` / `CHANGE_PERCENT_BELOW` | `{"threshold": -20}` | 盘中每 30 秒 | 涨跌幅阈值，百分数值，跌 20% 填 -20 |
| `MACD_GOLDEN_CROSS` / `MACD_DEATH_CROSS` | `{}` | 收盘后 15:40 | DIF 上穿/下穿 DEA，需 ≥35 根日 K |
| `KDJ_GOLDEN_CROSS` / `KDJ_DEATH_CROSS` | `{}` | 收盘后 15:40 | K 上穿/下穿 D，需 ≥10 根日 K |
| `RSI_CROSS_ABOVE` / `RSI_CROSS_BELOW` | `{"period": 6, "threshold": 80}` | 收盘后 15:40 | RSI 上穿/下穿阈值；`period` 只允许 6/12/24，`threshold` 0~100 |

指标类规则的三条约定（都写进了实现，不是"看情况"）：

1. **穿越语义，不是状态语义**：只有"上一根在另一侧、本根到这一侧"才触发。
   DIF 在 DEA 上方连着一周不会每天推一条；代价是规则新增时已经交叉过的不会补发。
2. **收盘后评估**：盘中当日 K 线未收盘，DIF 会在一天里反复穿越，用半根 K 线判金叉会推出"收盘并不存在"的信号。
3. **按交易日去重**：`deduplication_key = rule-{id}-{交易日}`，同一天重复评估（含手动补跑）不会重复推送；
   事件里 `observed_detail` 记录当根 K 线的指标值（`dif/dea/rsi6/k/d/j` 等），事后可复盘。

尚未实现：`PREMIUM_ABOVE`/`PREMIUM_BELOW`（溢价率，等 P1.4 的 IOPV 数据）、`INDICATOR_CROSS`（通用指标交叉，已由上面四个具体类型覆盖，不再单独实现）。

批量创建：`POST /alert-rules/batch`，请求 `{"instrumentIds":[3,4,5], "ruleType":"CHANGE_PERCENT_BELOW", "params":{"threshold":-20}, ...}`，一次为多个监控标的创建相同类型与参数的规则；返回逐条成功/失败结果，部分失败不影响其他标的。

响应 `201 Created`，返回规则字段及 `createdAt`。

### `POST /alert-rules/indicator/evaluate`

手动触发指标类规则评估（收盘后任务在 15:40 自动跑；这个入口用于补跑和验证，不必等到收盘）。

参数：`instrumentId`（可选，不传则评估全部启用中的指标类规则）。

```json
{"instruments": 1, "rules": 4, "triggered": 2, "elapsedMs": 3106, "adjust": "qfq",
 "results": [{"ruleId": 6, "ruleName": "茅台 RSI6 上穿70", "symbol": "600519",
              "ruleType": "RSI_CROSS_ABOVE", "status": "TRIGGERED", "tradeDate": "2026-10-26",
              "requiredBars": 8, "availableBars": 120, "message": "已生成告警事件并派发通知"}]}
```

`status` 六种取值，用来回答"为什么没提醒我"：

| status | 含义 |
|---|---|
| `TRIGGERED` | 触发并生成事件 |
| `NOT_TRIGGERED` | 最近两根没有穿越信号（当天已触发过的也会显示这个，被去重键抑制） |
| `SKIPPED_INSUFFICIENT_BARS` | K 线不足，`requiredBars` / `availableBars` 给出具体数字 |
| `SKIPPED_NO_KLINES` | 该标的还没同步过日 K，`message` 里给出同步命令 |
| `ERROR` | 参数或类型异常，`message` 为原因 |

### `PATCH /alert-rules/{id}`

允许更新 `name`、`ruleType`、`params`、`cooldownSeconds`、`enabled`。`ruleType` 可改：以目标类型校验参数（未传 `params` 时用现有参数校验，不兼容返回 `400`）。**标的不允许**用 PATCH 更换，需要换标的时应删除重建，历史事件快照不受影响。冷却时间统一以秒为单位存储（前端可按秒/分钟/小时输入换算）。

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

### `GET /portfolio/equity-curve`（P1.5）
参数：`from`、`to`（ISO 日期，均可省略）。省略 `to` 取库里最新快照日；省略 `from` 取 `to − 180 天`；区间上限 3 年。

```json
{"from": "2026-04-29", "to": "2026-10-26",
 "points": [
   {"tradeDate": "2026-10-26", "marketValue": 150240.00, "costValue": 150300.00,
    "unrealizedPnl": -60.00, "realizedPnlCum": 28.00, "totalPnl": -32.00,
    "positions": 1, "pricedPositions": 1, "complete": true}
 ],
 "summary": {"firstDate": "2026-04-29", "lastDate": "2026-10-26",
             "startMarketValue": 149000.00, "endMarketValue": 150240.00, "marketValueChange": 1240.00,
             "endCostValue": 150300.00, "endUnrealizedPnl": -60.00, "endRealizedPnlCum": 28.00,
             "endTotalPnl": -32.00, "netInvested": 150305.00, "returnPct": -0.02},
 "lastSnapshotDate": "2026-10-26", "priceAdjust": "raw",
 "note": "简单收益：总盈亏 = 当前市值 − 净投入资金……"}
```

口径（写在 `note` 里，前端原样展示）：

- 每个点是"该交易日收盘"的组合状态，由 `position_snapshot` 按日聚合；组合层不建表。
- `totalPnl = unrealizedPnl + realizedPnlCum`；`netInvested = Σ买入(金额+费用) − Σ卖出(金额−费用)`；
  `returnPct = totalPnl / netInvested`。**未做资金加权（TWR），也未调整分红/送股**，出入金会直接改变分母。
- `complete=false`：当天有持仓取不到收盘价，`marketValue/unrealizedPnl` 只汇总了能取到价的部分。
  最后一天 `complete=false` 时 `returnPct` 为 `null`（分子只算了一半，比值没有意义）。

### `GET /portfolio/position-changes`（P1.5）
参数同 `equity-curve`。返回区间内每个标的的期初/期末与变化量：

```json
[{"instrumentId": 2, "symbol": "510300", "name": "沪深300ETF华泰柏瑞",
  "openDate": "2026-10-23", "closeDate": "2026-10-26",
  "openQuantity": 1000, "closeQuantity": 600, "quantityDelta": -400,
  "openAvgCost": 4.105, "closeAvgCost": 4.105, "avgCostDelta": 0,
  "openMarketValue": 4100, "closeMarketValue": 2502, "marketValueDelta": -1598,
  "openUnrealized": 0, "closeUnrealized": 75, "unrealizedDelta": 75,
  "realizedPnlInRange": 28, "realizedPnlCum": 28, "tradeCount": 1, "closed": false}]
```

- 期初取"区间起点之前的最后一条快照"；没有则 `openDate=null`、期初数量按 0，表示区间内才建仓。
- 区间内开仓又清仓时，期初/期末都是 0，`quantityDelta=0`，用 `closed=true` + `realizedPnlInRange` 表达"中间发生过交易"。
- `realizedPnlInRange = 期末累计已实现 − 期初累计已实现`。

### `POST /portfolio/snapshots/rebuild`（P1.5）
参数：`from`、`to`，默认最近 30 天到"今天"（`Asia/Shanghai`）。改过流水后手动重建。

```json
{"from": "2026-09-26", "to": "2026-10-26", "tradeDates": 18, "instruments": 1, "rows": 18,
 "syncedInstruments": 1, "warnings": [], "elapsedMs": 820}
```

- 重建 = 先删区间再插（幂等），不做增量修补：快照不是事实来源，流水 + 收盘价才是。
- 收盘价用**不复权**（`raw`）口径；缺价时该行 `marketValue/unrealizedPnl` 为 `null`，`warnings` 说明原因。
- `warnings` 非空不等于失败。典型是标的缺不复权日线：先「同步历史」再重建。

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

### P1 数据适配器（内部契约，已实现）

仅供 Java 调用，不对前端暴露。

- `GET /internal/v1/kline?secid=1.600519&period=daily&adjust=qfq&limit=250&end=2026-10-26`
  - `secid` 由 Java 的 `SymbolMapping` 生成，Python 不掌握 A 股代码前缀规则（避免两份实现漂移）。
  - P1 仅支持 `daily`；`limit` 上限 500；返回 `bars` 升序。
  - 返回 `skippedBars` 报告上游脏行/重复行数量，Java 记入同步统计，不静默丢弃。
  - 错误码：`NO_DATA`(404) / `NOT_SUPPORTED`(400) / `UPSTREAM_ERROR`(502) / `UPSTREAM_TIMEOUT`(504)，结构同 §1。
  - 完整契约见 `backend-python/README.md`。
- `GET /internal/v1/fund-nav?fundCode=510300&limit=30`（P1.4 已实现）
  基金日终净值（天天基金 `lsjz`，带 `FSRQ` 净值日期）。`limit` 上限 250，上游单页只回 20 行，适配器内部翻页。
  返回 `{source,fundCode,requestedLimit,fetchedAt,totalCount,count,skippedRows,items[]}`。完整契约见 `backend-python/README.md`。

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
