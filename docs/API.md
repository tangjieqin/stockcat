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

### `POST /alert-events/{id}/explain`

AI 解释一条已触发的事件（P2 场景二）。**只读**：不写库、不改规则、不发通知；事件不存在返回 `404 NOT_FOUND`。

没有任何请求体。返回结构：

```json
{
  "eventId": 100,
  "symbol": "600519",
  "name": "贵州茅台",
  "ruleName": "茅台 MACD 金叉",
  "ruleType": "MACD_GOLDEN_CROSS",
  "ruleTypeLabel": "MACD 金叉",
  "condition": "条件是「DIF 上穿 DEA」（日线，收盘后判定）",
  "mode": "llm",
  "why": "2026-10-26 22:00，条件是「DIF 上穿 DEA」：DIF 0.0123 上穿 DEA 0.0101，条件成立，因此触发。",
  "meaning": "DIF 上穿 DEA，通常被看作短期动能相对转强的技术信号。MACD 属于滞后指标……",
  "current": "当前最新价 ¥1355.9，涨跌幅 +0.44%。最新已收盘日线（2026-10-26）上，DIF=0.008、DEA=0.0101，DIF 位于 DEA 下方（触发时在上方，已发生反向穿越）。",
  "evidence": ["触发时 DIF=0.0123、DEA=0.0101", "MACD 柱 +0.0044"],
  "limits": ["该事件由收盘后日线判定，不含盘中行情时间……"],
  "snapshotComplete": true,
  "generatedAt": "2026-10-27T07:10:00Z",
  "model": "deepseek-chat",
  "usage": {"model": "deepseek-chat", "promptTokens": 620, "completionTokens": 180},
  "degraded": [],
  "elapsedMs": 1840
}
```

字段约定：

- `why` / `meaning`：可读性文字。配置了模型时由模型改写措辞，否则模板生成。
- `current` / `evidence` / `limits`：**恒由后端模板生成，不经过模型**——数据依据不能被模型"润色"。
- `snapshotComplete=false`：事件快照缺少关键观测值（例如早期事件没存 RSI 数值）。此时 `why` 会明确写"快照没有保存 X，无法给出具体值"，且**不交由模型改写**（模型最容易在这里补一个合理数字）。
- `mode=rule_based` 时 `degraded` 必非空，前端要显示"这不是模型分析结果"。
- 数值口径：价格按原精度，指标值保留 4 位小数。
- AI 不可用返回 `503 DATA_SOURCE_UNAVAILABLE`，`GET /alert-events` 等接口不受影响。

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
| POST | `/recaps/ai-draft` | 生成 AI 草稿，**不落库**；请求 `{"date":"2026-10-27"}`（可省略，缺省今天） |
| POST | `/recaps/drafts` | 保存 AI 草稿为 `AI_DRAFT`；已有当天草稿则覆盖，已有 `MANUAL` 返回 `409` |

`marketSummary`（市场主要情况）与 `sectorNotes`（板块表现）为可选结构化字段，编辑页分区填写；用户手工创建的复盘 `status` 恒为 `MANUAL`，P2 的 AI 只能生成 `AI_DRAFT`，不得覆盖 `MANUAL`。

**AI 草稿两个入口的分工**（详见 PLAN_P2 §2 P2.3/P2.4）：

- `POST /recaps/ai-draft` 只是代理 Python `POST /agent/v1/recap/draft`，把生成结果原样返回给前端预览。
  生成前先查当天是否已有 `MANUAL`：有就 `409`，不花 token 生成一份注定不能保存的草稿。
- `POST /recaps/drafts` 才写库。请求体：

```json
{
  "recapDate": "2026-10-27",
  "title": "2026-10-27 复盘（AI 草稿）",
  "marketSummary": "…", "sectorNotes": "…", "content": "# …（Markdown）",
  "generatedAt": "2026-10-27T07:30:00Z",
  "model": "deepseek-chat",
  "dataAsOf": "2026-10-27T07:00:00Z",
  "limitations": ["指数与自选行情是采集时点快照，不是交易所官方收盘价。"]
}
```

`generatedAt` / `model` / `dataAsOf` / `limitations` 由生成接口原样回传，落库后作为溯源信息
（V8 的 `ai_*` 四列），**确认转 `MANUAL` 后仍保留**——它记录的是"这份复盘最初怎么来的"，不是当前状态。

复盘响应新增四个字段，手工创建的为 `null`：

```json
{"aiGeneratedAt": "2026-10-27T07:30:00Z", "aiModel": "deepseek-chat",
 "aiDataAsOf": "2026-10-27T07:00:00Z", "aiLimitations": ["…"]}
```

### 我的日记 `/diary`（树状长期笔记本）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/diary/tree` | 返回整棵树（id、parentId、nodeType、title、sortOrder、含子节点）；个人规模一次全量返回 |
| GET | `/diary/nodes/{id}` | 单节点详情（DOC 含 content） |
| POST | `/diary/nodes` | 创建：`{"parentId":5或null,"nodeType":"FOLDER"/"DOC","title":"...","content":"..."(仅DOC)}`；超深度限制返回 `400` |
| PATCH | `/diary/nodes/{id}` | 修改 `title`/`content`/`relatedInstrumentIds`；**移动**用 `{"parentId":X,"sortOrder":N}`，服务端做成环与深度校验，违规返回 `400` |
| DELETE | `/diary/nodes/{id}?confirm=true` | DOC 直接删除；FOLDER 级联删除整棵子树，**必须**带 `confirm=true` 且前端强确认显示将删除的文档数，否则返回 `400` |

删除返回 `{"deletedDocCount": N}`：删 DOC 为 `1`，删 FOLDER 为子树里的文档数——前端用它做删除后的提示。

搜索：`GET /diary/nodes?keyword=...` 对 DOC 的标题与正文做 `ILIKE` 匹配，返回命中文档列表（含其树路径）。

#### AI 辅助（P2 场景四，2026-10-27 已落地）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/diary/ai/refine` | 整理一篇日记：`{"nodeId":7,"title":"...","content":"..."}`。返回建议稿，**不写库** |
| POST | `/diary/ai/review` | 按关键词回顾：`{"keyword":"白酒","limit":5}`。先检索命中文档，再归纳，**不写库** |

两个接口都**不直接改正文**：整理结果回填到前端编辑器，用户点「保存」才走 `PATCH /diary/nodes/{id}`。
这和复盘草稿是同一条规矩——AI 不改用户已经写下的东西。

**`/diary/ai/refine`** 的请求字段：`nodeId` 可选（有值时用库里的标题/正文兜底），
但 `title` / `content` 传了就优先——用户在编辑器里改了还没保存时，整理的应该是眼前这一版。
正文为空或超过 20000 字返回 `400`（长文请先自行精简，AI 不做长文压缩）。

```json
{
  "title": "白酒复盘随手记",
  "mode": "llm",
  "result": {
    "summary": "一句话概括…",
    "highlights": ["要点一", "要点二"],
    "instruments": [{"symbol": "600519", "name": "贵州茅台"}],
    "followUps": ["待跟进：三季报渠道库存"],
    "refined": "整理后的正文（未保存，由用户决定写不写回）"
  },
  "knownInstrumentCount": 6,
  "limitations": ["仅整理本文内容，不引用外部资料，也不判断对错。"],
  "generatedAt": "2026-10-27T09:12:00Z",
  "model": "deepseek-chat",
  "usage": {"model": "deepseek-chat", "promptTokens": 1203, "completionTokens": 288},
  "degraded": [],
  "elapsedMs": 4210
}
```

- `knownInstruments` 由 Java 侧组装（自选在前、持仓补充，按代码去重）送给 Python。
  理由：Python 不连库，「这个标的存不存在」只有库知道。
- 模型给出的代码若不在白名单内直接丢弃，并在 `limitations` 里列出被丢弃的代码——
  要让用户看见"AI 认错了被我拦下"，而不是以为"AI 没认出来"。
- 模型输出缺 `summary` / `highlights` / `refined` 任一段即整篇退回规则提取，不拼半截；
  `instruments` 与 `followUps` 允许为空。
- 规则模式（`mode=rule_based`）用句子切分 + 标的词匹配产出 `summary`/`highlights`/`instruments`/`followUps`，
  **`refined` 恒为 `null`**——把原文换个说法不叫整理，只会让人误以为 AI 读懂了。`degraded` 里必须说明当前是哪种情况。
- 正文超过 6000 字时只有前 6000 字进 prompt，`limitations` 会写明截断；接口层的 20000 字上限是另一道闸。

**`/diary/ai/review`**：`keyword` 必填（不带词的"总结我的日记"等于让模型自由发挥），
`limit` 缺省 5、上限 10。命中为空时**不调模型**，直接返回 `empty=true` 与空的分段结构。

```json
{
  "keyword": "白酒",
  "hitCount": 3,
  "empty": false,
  "hits": [{"nodeId": 7, "title": "白酒复盘随手记", "path": "投资笔记/行业/白酒复盘随手记",
            "preview": "…命中片段…", "updatedAt": "2026-10-26T10:00:00Z"}],
  "mode": "llm",
  "theme": "白酒仓位的三次调整",
  "threads": [{"title": "白酒复盘随手记", "date": "2026-10-26", "path": "投资笔记/行业", "point": "减仓理由是渠道库存"}],
  "recurring": ["反复出现的判断：渠道库存是核心变量"],
  "gaps": ["2026-08 之后再没记过白酒"],
  "citations": ["《白酒复盘随手记》2026-10-26"],
  "toolCalls": [{"tool": "diary-search", "label": "检索日记", "ok": true, "summary": "3 篇命中"}],
  "limitations": ["只基于命中的日记，未检索新闻与公告，不构成投资建议。"],
  "generatedAt": "2026-10-27T09:15:00Z",
  "model": "deepseek-chat",
  "usage": {"model": "deepseek-chat", "promptTokens": 2310, "completionTokens": 512},
  "degraded": [],
  "elapsedMs": 5120
}
```

- `citations` 只认命中列表里的篇目：模型引用了列表外的标题，整段替换为由命中列表自拼的引用。
  引用错就是编造，比没有引用更糟。
- 检索工具失败（`toolCalls[].ok=false`）时同样不进模型，`empty=true` + `limitations` 记失败原因。

#### 资料库 `/library`（P3.1，外部资料的导入与检索）

与「我的日记」分开：日记是用户写的、可改；资料是外部内容、**导入后正文不可编辑**，
要换内容就重新导入。两者混在一起会让"引用指向哪一版"说不清。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/library/documents` | 新建资料：`{"title","sourceType","sourceRef","publisher","publishedAt","content","instrumentIds":[],"note"}` |
| POST | `/library/documents/import` | 上传文本文件（multipart）：`file` + 可选 `title` / `publisher`；只收 `.md` / `.markdown` / `.txt` |
| GET | `/library/documents?keyword=&page=&size=` | 列表（不含正文），`keyword` 匹配标题与正文 |
| GET | `/library/documents/{id}` | 详情：含正文、全部片段、关联标的 |
| GET | `/library/search?keyword=&limit=&instrumentId=` | **片段级检索**（P3.2），见下 |
| DELETE | `/library/documents/{id}?confirm=true` | 删除资料，片段由外键级联删除；未带 `confirm` 返回 `400` 并告知将删几个片段 |

`sourceType` 只接受 `PASTE` / `FILE`（`URL` 在表结构里预留，接口暂不开——没有验证过的抓取能力）。
上传 `.pdf` / `.html` 会被 `400` 拒绝并说明替代做法，不静默失败：
PDF 没有段落概念、HTML 会带导航与页脚，脏文本会一路脏到检索结果。

```json
{
  "id": 1, "title": "白酒渠道调研纪要", "sourceType": "PASTE", "sourceRef": null,
  "publisher": "某券商", "publishedAt": "2026-09-20",
  "importedAt": "2026-10-27T11:09:30Z", "charCount": 171, "chunkCount": 1,
  "status": "ACTIVE", "note": null,
  "content": "…规范化后的全文…",
  "instrumentIds": [],
  "chunks": [{"id": 1, "seq": 1, "heading": "渠道跟踪", "content": "…", "charStart": 8, "charEnd": 171}]
}
```

**去重**：按 `sha256(规范化正文)` 唯一索引拦截。重复导入返回 `409`，
message 里带已存在的 id（`这篇资料已在库中（id=1），未重复导入`），前端据此提示并跳转。
规范化 = 统一换行、去行尾空白、压缩连续空行、去首尾空白、去 BOM——
同一篇资料不同排版应被视为同一篇。

**切片**（决定引用能不能对得上）：
- 优先在段落边界切，合并到 300–600 字；单段超长退到句子，仍超长才按 600 字硬切；
- Markdown 标题（`#` 开头）写入片段的 `heading`，其后的段落继承它；
- 相邻片段做**整段重叠**：上一片段最后一段带到下一片段开头，避免答案正好落在切口上；
- `charStart` / `charEnd` 是相对**规范化后正文**的字符区间（左闭右开），引用一律按它回原文取完整片段，
  不用检索时的截取结果——截断会让引用在语义上失真；
- 偏移口径是 Java 的 `char` 下标（UTF-16 code unit），引用由 Java 侧 `substring` 取，内部自洽。

上限：单篇正文 20 万字、单篇 800 个片段，超过返回 `400`（说明该拆分了，而不是截断）。

#### 片段级检索 `GET /library/search`（P3.2）

命中单位是**片段**而不是整篇：用户搜"渠道库存"想知道的是"哪一段在讲这个"，
整篇命中只能告诉他"这篇里某个地方提过"，还得自己翻。

| 参数 | 说明 |
|---|---|
| `keyword` | 必填。空白返回 `400`——空查询不能退化成"列出全部片段" |
| `limit` | 默认 20，上限 50 |
| `instrumentId` | 只看关联了某标的的资料（可选） |

```json
{
  "keyword": "渠道库存",
  "terms": ["渠道库存"],
  "hits": [{
    "chunkId": 4, "documentId": 4, "documentTitle": "三季报点评：600519",
    "seq": 1, "heading": "三季报要点",
    "snippet": "…经销商反馈渠道库存仍处高位，公司对批价的管控意愿强烈…",
    "charStart": 0, "charEnd": 178,
    "sourceType": "PASTE", "sourceRef": null, "publisher": "某基金",
    "publishedAt": "2026-10-25", "importedAt": "2026-10-27T11:19:33Z",
    "score": 6, "matchedTerms": ["渠道库存"], "titleMatched": false
  }]
}
```

**拆词**（`LibrarySearchTerms`）：
- 先按标点/空白切 token，再各自拆 bigram。"白酒 库存"不会生成跨词的"酒库"碎片；
- 单字不进 bigram（"的"、"了"命中率太高，会召回整库），只有整条查询只有一个字时才用它兜底；
- 全角数字转半角（从 PDF/Excel 复制的"６００５１９"否则永远匹配不上）；
- **不过滤停用词**：P3.0 实测加了词表 precision 只从 0.631 到 0.639，不值得维护。

**门槛**（两条，都是 P3.4 评估倒逼出来的，见 `PLAN_P3_EVAL.md` §8.4）：

- **命中 bigram ≥ 2** 才进结果。长查询会拆出「周期」「变化」「订单」这类通用实词，
  只靠一个碎片命中就返回，会出现"搜『猪周期能繁母猪存栏』返回一堆讲白酒周期的段落"。
  只有 1 个 bigram 的查询（2 字词）不设此限——那是精确匹配，不存在这种误报。
- **多词查询时每个词都要命中**。「黄金 ETF 持仓变化」曾被拆成 6 个 bigram，
  一篇讲宽基 ETF 的笔记靠 ET/TF 和"变化"就能进结果，而"黄金"一次没出现。
  这条只在查询被切成多个 token 时生效：「茅台批价」在中文里没有空格，是单个 token，
  不受影响（否则原文里"茅台"与"批价"分处两段时会被误判成无关）。

**排序**：`score = 整词命中数 × 3 + bigram 命中数`，再按
`score DESC → occurrences DESC → publishedAt DESC NULLS LAST → importedAt DESC → chunkId`。
发布日未知的排后面，而不是当作最新或最旧。

`occurrences` 是命中词在该片段里出现的总次数（同分时的第二排序键）：讲渠道库存的段落会提
3 次，顺带蹭到一次的排后面。**它刻意不进 `score`**——score 的口径要和 P3.0 评估脚本对得上，
而且长片段天然出现更多次，直接加权会让"长"冒充"相关"。实测这一维对 `recall@5` 没有影响
（0.882 → 0.882），它只解决"同分按发布时间排毫无道理"这个问题，见 `PLAN_P3_EVAL.md §8.10`。

**三条约束**（都由 P3.0 评估或实测倒逼出来的）：
- 检索列是「标题 + 正文」：用户常说"我记得有一篇讲渠道库存的"，但正文里未必写着标题里的词。
- 同一篇最多贡献 3 个片段。标题命中会让该篇每段都得分，不限就是同一篇占满整页。
- 某篇既然有正文真命中，就丢掉它"仅标题命中"的片段；整篇都只是标题命中的，只留一条。
  否则搜"调研纪要"会返回同一篇的三段，其中两段正文里根本没有这个词。这类命中带
  `titleMatched=true`，前端标"仅标题命中"，避免用户以为高亮坏了。

`matchedTerms` 只返回**实际出现在正文里**的词，且去掉被整词覆盖的碎片：
"600519" 不会连带返回 `60/00/05/51/19`，否则前端高亮会把代码标成一串色块。

无结果时返回空 `hits`，不返回"相关推荐"——没有就是没有。
关键词只匹配原文，不做同义词扩展，这一点在页面上也写明。

**返回条数可以少于 `limit`**：门槛与收窄都会减少条数，「白酒的渠道库存到底消化了没有」
这类长查询可能只返回 1–2 条。这是刻意的——凑满 5 条就要拿噪声充数，而"只找到 2 条"
本身就是质量信号。前端不要补占位，也不要提示"结果较少"。

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
  "indicesConfigured": 3,
  "pythonService": {
    "status": "UP",
    "baseUrl": "http://127.0.0.1:8100",
    "agent": {"status": "UP", "modelConfigured": true, "model": "gpt-4o-mini", "toolCount": 6}
  },
  "jobs": {
    "history-sync": {"status": "UP", "lastAttemptAt": "2026-10-27T07:35:02Z", "lastSuccessAt": "2026-10-27T07:35:02Z",
                     "lastError": null, "consecutiveFailures": 0, "summary": "标的 3 成功 3 失败 0 跳过 0",
                     "attempts": 1, "successes": 1},
    "fund-nav-sync": {"status": "UNKNOWN", "lastAttemptAt": null, "lastSuccessAt": null, "lastError": null,
                      "consecutiveFailures": 0, "summary": null, "attempts": 0, "successes": 0},
    "position-snapshot": {"status": "UP", "summary": "2026-09-27~2026-10-27 交易日 20 行 41 提示 0"},
    "indicator-alert": {"status": "FAILED", "lastError": "K 线不足", "consecutiveFailures": 1}
  },
  "dataSources": [
    {"key": "quote", "name": "实时行情", "provider": "东方财富 push2",
     "baseUrl": "https://push2.eastmoney.com", "enabled": true,
     "schedule": "交易时段每 15 秒", "cron": null,
     "note": "自选、监控标的与指数的现价；未开市或超时按 STALE 标注，缺失字段一律 null。",
     "stats": {"status": "UP", "lastSuccessAt": "2026-10-27T07:59:45Z", "lastError": null,
               "consecutiveFailures": 0, "summary": "成功 120 次、失败 3 只次"}},
    {"key": "fund-quote", "name": "基金 IOPV", "provider": "腾讯行情", "baseUrl": "https://qt.gtimg.cn",
     "enabled": true, "schedule": "随实时行情采集", "cron": null,
     "note": "ETF 的盘中参考净值；LOF 没有 IOPV，只能等当晚净值。本项无独立运行统计。", "stats": null}
  ]
}
```

`pythonService` 是**真实探活**（`GET /agent/v1/health`，3s 上限）：

- Python 没起来时 `status=DOWN` 并带可读的 `error`，**不带 `agent`**；本接口仍返回 `200`，
  因为"AI 没开"是正常状态，不是系统故障。
- `agent` 只报"配没配模型、几个工具"，不搬整个白名单（要看清单用 `/assistant/status` 或 §10）。
- 探活最多多等 3s；行情采集与告警在别的线程，不受影响。

`jobs` 是收盘后四个任务的**内存态**统计（`JobRunStats`）：

- `status` 三态：`UP`（上次成功）/ `FAILED`（最近一次失败，带 `lastError` 与连续失败次数）/ `UNKNOWN`（本进程启动后没跑过）；
- **Java 重启后清零**，页面上也写明这一点——把 UNKNOWN 当故障排查是常见误会；
- 失败不擦除 `lastSuccessAt`，那个时间是历史事实。

`dataSources` 供「数据源」页使用，`stats` 与 `jobs` 同结构，**没有独立统计的源写 `null`**
（例如 IOPV 随实时采集执行，不单独立项），前端显示"—"而不是编一个"正常"。

不要在该接口返回密码、token、环境变量值或堆栈。

## 10. Agent 只读工具与 AI 助手（P2）

供 Python Agent 取数的白名单接口，前缀 `/api/v1/agent/tools`。**全部 GET、全部只读**：
`AgentToolControllerTest` 用反射断言这里不存在任何写映射，新增工具必须同时改代码与白名单。

统一包装（每个工具都返回这个结构）：

```json
{
  "tool": "positions",
  "dataAsOf": "2026-10-27T06:30:00Z",
  "data": { },
  "missing": ["510300 沪深300ETF 缺有效价格（priceStatus=STALE），市值未计入汇总"]
}
```

- `dataAsOf` 是数据自身的观测时点（取内部最新时间戳），**不是**请求时间；AI 复盘里的"数据截至"必须用它。
- `missing` 是"本该有但没有"的说明，空数组表示数据完整。数值取不到一律 `null`，**不补 0**。
- 缺失/异常不算 HTTP 错误：工具照常 200 返回，缺失信息进 `missing`，由模型在正文里如实说明。

### `GET /agent/tools/watchlist-quotes`

自选行情。无快照的标的仍然出现在 `items` 里（价格为 `null`、`dataStatus=NO_DATA`）并进 `missing`；
`groupName` 为 `null` 时输出默认分组名 `自选`。

```json
{"tool":"watchlist-quotes","dataAsOf":"2026-10-27T06:30:00Z",
 "data":{"count":1,"items":[{"instrumentId":1,"symbol":"600519","name":"贵州茅台","type":"STOCK",
   "groupName":"自选","lastPrice":1700.0,"previousClose":1674.9,"changePercent":1.5,
   "marketTime":"2026-10-27T06:30:00Z","collectedAt":"2026-10-27T06:30:02Z","dataStatus":"VALID"}]},
 "missing":[]}
```

### `GET /agent/tools/positions`

当前持仓与汇总。`priceComplete=false` 表示有持仓缺有效行情，此时
`totalMarketValue`/`totalUnrealizedPnl` 只是"能取到价的部分"，不能当全组合市值。

```json
{"tool":"positions","dataAsOf":"2026-10-27T06:30:00Z",
 "data":{"count":1,"totalMarketValue":170000,"totalUnrealizedPnl":2000,"totalRealizedPnl":0,
   "pricedCount":1,"priceComplete":true,"note":"市值与浮动盈亏只基于有效行情快照…",
   "items":[{"instrumentId":1,"symbol":"600519","name":"贵州茅台","quantity":100,"avgCost":1680,
     "lastPrice":1700,"marketValue":170000,"unrealizedPnl":2000,"realizedPnl":0,
     "priceAsOf":"2026-10-27T06:30:00Z","priceStatus":"VALID"}]},
 "missing":[]}
```

### `GET /agent/tools/alert-events?limit=10`

最近告警事件（按触发时间倒序）。`limit` 默认 10、上限 50。没有告警是正常状态，不进 `missing`。

### `GET /agent/tools/market-brief?date=2026-10-27`

当日市场概况：指数行情 + 自选涨跌分布 `{total,up,down,flat,noData,avgChangePercent}`。
只有 `dataStatus=VALID` 的行情参与涨跌统计（STALE 的涨跌幅是过时的）；`avgChangePercent` 无可统计样本时为 `null`。

`date` 只表示这份复盘的归属日期：**指数只有最新采集快照，不回补历史**，传入非今日日期会在 `missing` 中明确说明。
`indices` 为空表示当前没有有效指数快照（常见于非交易时段重启服务，要等下一个采集窗口才有值），此时 `dataAsOf` 退化为自选行情的采集时间。

### `GET /agent/tools/recaps?from=&to=&limit=5`

历史复盘摘要（按日期倒序，正文截断为 240 字预览）。`limit` 默认 5、上限 20。
**只返回 `status=MANUAL`**：AI 草稿不当作历史依据，被排除的草稿数写进 `missing`。

### `GET /agent/tools/diary/search?keyword=白酒&limit=5`

日记正文检索，返回命中标题、树路径（`投资 / 复盘`）与正文预览。`keyword` 必填（空关键词返回校验错误），
`limit` 默认 5、上限 20。日记没有行情时间语义，`dataAsOf` 为 `null`。

### `GET /agent/tools/library/search?keyword=渠道库存&limit=5`

资料库片段检索（P3.3），命中单位是**片段**。`keyword` 必填，`limit` 默认 5、上限 20。

- 每条命中带 `citation`，格式固定 `《标题》· 第 N 段`，**由服务端按真实的 `seq` 拼装**；
  模型只允许原样引用这串字符，不许自述出处（Python 侧 `library_guard` 会删掉命中列表以外的引用）。
- `dataAsOf` 取命中片段中最新的 `importedAt`——它表达"这些资料在库中的时间"，不是行情时间。
- 没命中时进 `missing`，并区分两种情况：`资料库里还没有任何资料`（共 0 篇）与
  `资料库共有 N 篇资料，但没有命中「X」的片段`。Python 侧对零命中**直接不调模型**，
  结论固定写"资料库里没有找到与「X」相关的资料"。

### `POST /assistant/ask`（前端唯一入口）

前端只跟 Java 说话，所以 AI 助手由 Java 代理到 Python `POST /agent/v1/assistant/ask`（契约见 §11）。
Java 不改写字段，只做超时（60s）与错误翻译。

```json
{"question": "今天我的持仓发生了什么变化？"}
```

响应（由 Python 生成，Java 原样返回）：

```json
{
  "mode": "rule_based",
  "answer": {"conclusion": "当前持有 1 只标的，合计市值 2,511.60 元…", "evidence": ["510300 …"], "limits": []},
  "toolCalls": [{"tool": "positions", "label": "查询持仓", "ok": true, "summary": "1 只持仓", "elapsedMs": 143}],
  "citations": [{"chunkId": 13, "documentId": 7, "title": "白酒渠道调研纪要", "seq": 1, "citation": "《白酒渠道调研纪要》· 第 1 段"}],
  "dataAsOf": "2026-10-27T01:26:25Z",
  "generatedAt": "2026-10-27T06:49:57Z",
  "model": null,
  "usage": null,
  "degraded": ["未配置模型…以下内容由规则汇总生成"],
  "elapsedMs": 144
}
```

- `mode=llm` 为模型生成，`mode=rule_based` 为规则汇总；`degraded` 非空时前端必须展示。
- `citations` 是本次回答引用的资料片段（P3.3）：`citation` 由 Java 工具拼好，`documentId`/`seq`
  供前端跳转 `/library?doc={id}&chunk={seq}`（资料页用完即从地址栏抹掉参数，不是可分享链接）。
  回答引用了其中几条就列几条；一条都没对上（规则模式）时列全部命中。
  没调资料工具时为空列表。
- 请求体 `question` 为空或超 500 字返回 `400 VALIDATION_ERROR`。
- Python 未启动 / 超时返回 `503 DATA_SOURCE_UNAVAILABLE`，错误信息直接给出启动命令。

### `GET /assistant/status`

AI 服务与模型配置状态，供「AI 配置」页显示：`{available, agent:{status, modelConfigured, model, tools}, error?, baseUrl}`。
**从不回显 API Key**，也不做模型探活请求。

## 11. Python API（分阶段）

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

### P2 Agent（已实现，内部契约）

- `GET /agent/v1/health` → `{status, modelConfigured, model, tools[]}`；不回显 key、不发探活请求。
- `POST /agent/v1/assistant/ask` 请求 `{"question":"..."}`，响应见 §10 的同名说明（Java 代理原样返回）。
- `POST /agent/v1/alerts/explain` 告警解释（P2 场景二，对应 §5 的 Java 接口）。

```json
{
  "event": {
    "id": 100, "symbol": "600519", "name": "贵州茅台",
    "ruleName": "茅台 MACD 金叉", "ruleType": "MACD_GOLDEN_CROSS",
    "params": {}, "observedPrice": 1500.0, "observedChangePercent": 2.35,
    "observedDetail": {"tradeDate": "2026-10-26", "dif": 0.0123, "dea": 0.0101, "macdHist": 0.0044},
    "triggeredAt": "2026-10-26T14:00:00Z", "quoteMarketTime": "2026-10-26T07:00:00Z"
  },
  "current": {
    "lastPrice": 1520.0, "changePercent": 1.2, "dataStatus": "VALID",
    "marketTime": "2026-10-27T06:30:00Z", "collectedAt": "2026-10-27T06:30:20Z",
    "latestTradeDate": "2026-10-27", "indicators": {"dif": 0.008, "dea": 0.0101}, "barsAvailable": 250
  }
}
```

`event` 由 Java 从 `alert_event` 原样读出（`observedDetail` 为 null 时就是 null，**不补算**）；
`current` 中 `indicators` 仅在指标类规则时出现，`premium` 仅在溢价类规则
（`PREMIUM_ABOVE` / `PREMIUM_BELOW`）时出现（2026-10-27 补）：

```json
"premium": {
  "premiumRate": 1.18, "premiumBasis": "IOPV", "premiumAsOf": "2026-10-27T06:30:00Z",
  "price": 0.905, "iopv": 0.894, "nav": null, "navDate": null, "navStaleDays": null
}
```

溢价事件里"现在溢价多少"就是答案的一半：触发时 3.1%、现在回到 1.2%，说明机会已经消失。
所以 Python 侧会做前后对比（`回落 N 个百分点` / `又上升 N 个百分点` / `基本持平`）；
**基准不同时拒绝做减法**——触发时用单位净值、当前用实时 IOPV，滞后性完全不同，
硬减会给出"回落 0.3 个百分点"这种看着精确、实际无意义的结论，此时只列数值并写进 `limits`。
标的不是基金或基金参考数据缺失时 `premium` 为 `null`（不是 0），解释里如实说"无法判断溢价是否回落"。

⚠️ **加字段时必须同步改 `backend-python/app/schemas.py` 的 `AgentAlertCurrentState`**。
Pydantic 默认丢弃未声明的字段，且**不报错**——这条通路第一次接的时候就断在这里：
Java 明明查了、也发了，Python 侧读到的仍是 `None`，解释里一直写着"当前没有可用的溢价数据"，
而两侧的单测都是绿的（Python 单测直接喂 dict，绕过了 schema）。
现在 `tests/test_alert_explain.py::test_request_schema_keeps_premium_snapshot` 专门守这一层。

Python 不做任何指标计算，只把这两块事实讲清楚，
因此不存在"用今天的 K 线重算当年指标"的问题。

响应结构见 §5 `POST /alert-events/{id}/explain`，两者字段完全一致（Java 原样代理）。

- `POST /agent/v1/recap/draft` 生成每日复盘草稿（P2 场景三）。请求 `{"date":"2026-10-27"}`（可省略，缺省今天）。

```json
{
  "date": "2026-10-27",
  "mode": "rule_based",
  "draft": {"title": "2026-10-27 复盘（AI 草稿）", "marketSummary": "…", "sectorNotes": "…", "content": "# …"},
  "limitations": ["指数与自选行情是采集时点快照，不是交易所官方收盘价。", "…"],
  "generatedAt": "2026-10-27T07:30:00Z",
  "dataAsOf": "2026-10-27T07:00:00Z",
  "toolCalls": [{"tool": "market-brief", "label": "查询市场概况", "ok": true, "summary": "3 个指数"}],
  "model": null,
  "usage": null,
  "degraded": ["未配置模型（STOCKCAT_LLM_* 为空），以下内容由规则底稿生成，不是模型撰写"],
  "elapsedMs": 186
}
```

固定取 5 个工具（`market-brief` / `positions` / `watchlist-quotes` / `alert-events` / `recaps`），
不做关键词规划——写一篇复盘需要的上下文是确定的。**不落库**：落库与确认在 Java 侧（§7）。

- 无任何数据（指数、自选、持仓全空）时返回 `502 NO_DATA`，不生成"今天市场平淡"这类空话；
- `limitations` 恒由模板按工具返回 `missing` 生成，模型输出里的限制项**一概不采信**；
- 行业板块数据未采集，`sectorNotes` 只能是自选分组汇总，并保留"非行业板块"的限定；
- 模型输出缺任一段（title/marketSummary/sectorNotes/content）即整篇退回规则底稿，不拼半截。

知识边界（实现中固定下来，后续不得放宽）：
- 工具白名单硬编码在 `backend-python/app/agent/java_tools.py`，与 Java 侧 `AgentToolController` 两份清单保持一致；
- 数值只能来自工具返回，缺数据写 `limits`，**不补 0、不推测**；
- 不输出买卖建议；模型输出非 JSON 即失败并降级为规则汇总，不去猜；
- 告警解释的 `current` / `evidence` / `limits` 恒由模板生成，模型只能改写 `why` / `meaning`；
  快照不完整时连 `why` 也不交给模型；
- 日志只记模型名/耗时/token，不记 key 与完整 prompt。

`POST /agent/v1/chat`（多轮对话）**未实现**，P2 不做多轮与会话记忆；当前会话仅存在前端 localStorage。

- `POST /agent/v1/diary/refine` 整理日记（P2 场景四）。
  请求 `{"title":"...","content":"...","knownInstruments":[{"symbol":"600519","name":"贵州茅台"}]}`，
  响应即 §7 `POST /diary/ai/refine`（Java 原样代理）。
  规则提取读全文，模型只读前 `MAX_TEXT_CHARS`(6000) 字，超出时 `limitations` 注明截断。

- `POST /agent/v1/diary/review` 日记回顾（P2 场景四）。
  请求 `{"keyword":"白酒","limit":5}`，先跑 `diary-search` 工具，命中为空**不调模型**，
  返回 `empty=true` 与空的 `theme`/`threads`/`recurring`/`gaps`/`citations`。

两个接口都**不落库**：整理稿写不写回正文，由用户在编辑器里点保存决定（走 §7 的 PATCH）。
`refine` 与 `review` 的校验规则（白名单丢弃、引用只认命中标题、缺段整篇回退）见 §7。

### P2.6 运行日志（实现约定）

五个场景（问答 / 告警解释 / 复盘草稿 / 日记整理 / 日记回顾）每次调用写**一行** `key=value`，
logger 名 `stockcat.agent`：

```
2026-10-27 15:42:11,203 INFO stockcat.agent agent scene=recap date=2026-10-27 mode=llm
  model=gpt-4o-mini elapsedMs=8123 tools=market-brief:ok,positions:ok usage=1234+312 err=-
```

| 字段 | 含义 |
|---|---|
| `scene` | `assistant` / `alert` / `recap` / `diary-refine` / `diary-review` |
| `mode` | `llm` 或 `rule_based`；拒绝生成时为空；日记回顾命中为空写 `empty` |
| `tools` | `工具名:ok/fail` 列表；告警解释为 `-`（数据由 Java 组装，不经工具）；日记整理无工具调用 |
| `usage` | `promptTokens+completionTokens`，未知写 `-` |
| `err` | 模型侧错误码（`MODEL_TIMEOUT` / `MODEL_ERROR` / `MODEL_OUTPUT_INVALID`）或业务码（如 `NO_DATA` / `TOOL_FAILED`） |
| `errText` | 截断到 120 字符、压成一行的错误说明 |

日记两个场景额外记 `chars`（整理：正文字数）、`instruments`（整理：识别出的标的数）、
`keyword` / `hits`（回顾：检索词与命中篇数）——这几个数用来判断"效果差"是输入问题还是模型问题。

**不记** API Key、prompt 原文、回答正文。失败与拒绝生成也要写一条，否则"用户说点了没反应"时无从查起。

## 12. 兼容性与版本策略

- P0 API 前缀固定为 `/api/v1`。
- 字段改名、语义变化或删除属于破坏性变更，需要更新文档、前端类型和测试。
- 新增可选字段优先保持向后兼容。
- 每个接口至少有一个成功测试和关键错误测试。

---

## 13. 数据库备份（2026-10-27，P4 前置小件）

只做导出与文件管理。**恢复不在 API 层提供**：`pg_restore` 灌回已有库会撞主键，或需要先清空
整个 schema，一次误点就没了全部流水——这一步只能走命令行，见 `DATABASE.md §7`。

### `GET /backup`

返回导出方式与已有文件：

```json
{
  "dir": "D://Code//stockcat//backups",
  "method": "docker exec stockcat-postgres pg_dump -Fc -U stockcat -d stockcat",
  "restoreCommand": "docker exec -i stockcat-postgres pg_restore --clean --if-exists -U stockcat -d stockcat < <备份文件完整路径>",
  "usesDocker": true,
  "files": [
    {"name": "stockcat-20261027-215709.dump", "sizeBytes": 209508,
     "createdAt": "2026-10-27T13:57:09.768Z"}
  ]
}
```

- `dir` 是**绝对路径**：相对路径在 IDE 与命令行下指向不同地方，页面直接显示文件落在哪。
- `method` 是当前实际会执行的命令，便于排查"为什么导不出来"；**不含口令**——容器内走
  unix socket（trust），本机 pg_dump 走 `PGPASSWORD` 环境变量，不进命令行参数。
- `restoreCommand` 由后端拼（而不是前端写死），因为它和导出共用同一份容器名/库名配置；
  文件路径留成占位符，由用户替换。
- `files` 按时间倒序，只认 `stockcat-YYYYMMDD-HHmmss(-N).dump`，目录里的其他文件不出现。
  **备份历史不建表**：把"有哪些备份"记在数据库里，等于把钥匙锁在要救的那间屋子里。

### `POST /backup/export`

同步执行一次导出，返回新文件记录。实现要点：

- 先写 `.part` 临时文件，`pg_dump` 退出码 0 且输出非空后才改名成正式文件——
  **失败不留半截 dump**。半截文件比没有更危险：真到要恢复那天才发现打不开。
- 同一秒内点两次，第二份加 `-2` 后缀，不覆盖已有备份。
- 超时 5 分钟；个人库实测 0.5 秒完成（约 20 万字节）。

### `GET /backup/{name}/download`

`application/octet-stream` 下载。`name` 必须匹配文件名正则，**非法名字一律 404**
（不提示"格式不对"，避免变成探测工具），另有目录归一化双重校验。
实测 `..%2F.env`、`../../../.env` 均被拒绝（400/404），`.env` 内容不出现在响应里。

### `DELETE /backup/{name}`

删除单个备份文件，返回 204。删的是备份文件本身，不影响数据库内容。

### 错误

依赖不可用（docker 没装、容器没起、pg_dump 路径配错、认证失败）返回
`503 BACKUP_UNAVAILABLE`，`message` 里写下一步该做什么：

| 场景 | message 要点 |
|---|---|
| 找不到 docker 命令 | 两个解决方式：启动 Docker Desktop；或装本机 pg_dump 并配 `stockcat.backup.pg-dump` |
| 容器不存在 | "容器 X 不存在或未运行。先执行 docker compose up -d"（附 pg_dump 原始输出） |
| Docker 守护进程没起 | "连不上 Docker 守护进程，请确认 Docker Desktop 已启动" |
| pg_dump 路径配错 | "找不到可执行的 X。请检查配置项 stockcat.backup.pg-dump" |
| 认证失败（仅本机模式） | 提示检查 `.env` 的 `STOCKCAT_DB_PASSWORD` |

三种场景均已实测（正常导出、容器不存在、路径配错）。

### 配置

```yaml
stockcat:
  backup:
    dir: ${STOCKCAT_BACKUP_DIR:../backups}        # spring-boot:run 的工作目录是 backend-java，../ 落到项目根
    docker-container: ${STOCKCAT_DB_CONTAINER:stockcat-postgres}
    pg-dump: ${STOCKCAT_PG_DUMP:}                  # 填本机 pg_dump 完整路径则走本机，留空走 docker exec
```
