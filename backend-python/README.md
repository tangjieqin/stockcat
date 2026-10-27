# StockCat Python 后端

FastAPI 服务，当前承担 **P1 历史行情适配** 与 **P2 AI 助手 Agent**。

**职责边界（重要）**：本服务只做「抓取 + 字段映射 + 错误翻译」与「编排只读工具 + 生成草稿」，不连业务数据库、不写业务表、不做业务取舍。
复权是否可用于指标、非正值 bar 是否入库、当日未收盘 bar 是否拒写——**全部由 Java 判定**；
AI 也只能调用 Java 的只读工具，改不了流水/规则/通知。

## 本地运行

```bash
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8100
```

- 健康检查: http://localhost:8100/health
- 接口文档: http://localhost:8100/docs

> Windows 没有系统时区库，`zoneinfo("Asia/Shanghai")` 依赖 `tzdata` 包，已写入 `requirements.txt`。
> 端口以本 README 的 8100 为准（`docs/ARCHITECTURE.md` 里的 8000 为早期约定）。

## 内部接口

仅供 Java 服务调用，不对前端暴露。

### `GET /internal/v1/kline`

日 K 线（升序，取尾部 `limit` 根）。

| 参数 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `secid` | 是 | — | 东财 secid：`1.` 沪市 / `0.` 深市，形如 `1.600519`。**由 Java 侧 `SymbolMapping` 生成**，Python 不掌握 A 股代码规则 |
| `period` | 否 | `daily` | P1 仅支持 `daily`，其他值返回 `400 NOT_SUPPORTED`（不做未验证的周期） |
| `adjust` | 否 | `qfq` | `raw` / `qfq` / `hfq` |
| `limit` | 否 | `250` | 1~500（上限防前复权长期负价） |
| `end` | 否 | 今天 | 截止交易日，格式 `YYYY-MM-DD`，按 Asia/Shanghai 计 |

响应示例（真实数据，2026-10-26）：

```json
{
  "source": "eastmoney-push2his",
  "secid": "1.600519",
  "symbol": "600519",
  "period": "daily",
  "adjust": "qfq",
  "requestedLimit": 3,
  "fetchedAt": "2026-10-26T11:16:58+00:00",
  "barCount": 3,
  "skippedBars": 0,
  "bars": [
    {
      "date": "2026-10-26",
      "open": 1325.4, "high": 1350.0, "low": 1324.79, "close": 1349.98,
      "volume": 41599.0, "amount": 5574287630.0,
      "changePercent": 1.12, "turnoverRate": 0.33
    }
  ]
}
```

- `volume` 单位是 **手**（1 手 = 100 股/份），`amount` 单位是 **元**
- `skippedBars > 0` 表示上游存在脏行/重复日期，Java 应记录并从同步统计里体现（不静默）
- `bars` 已按日期升序；同一天重复时保留后出现的一行

### `GET /internal/v1/fund-nav`

基金日终净值（P1.4）。数据源：天天基金 `f10/lsjz`，固定带 `Referer`。

```
GET /internal/v1/fund-nav?fundCode=510300&limit=30
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `fundCode` | — | 6 位基金代码（ETF/LOF 都支持） |
| `limit` | 30 | 最近 N 期，1~250；**上游单页只回 20 行，>20 会自动翻页** |

```json
{"source":"eastmoney-f10-lsjz","fundCode":"510300","requestedLimit":30,
 "fetchedAt":"2026-10-26T14:40:00Z","totalCount":3517,"count":30,"skippedRows":0,
 "items":[{"date":"2026-10-26","nav":4.1797,"accNav":1.8769,"changePercent":-0.03}]}
```

- `items` 按日期**倒序**（与上游一致）；`navDate` 就是 `date`，Java 侧据它判断净值滞后几天
- 非法代码返回 `404 NO_DATA`（上游 `ErrCode=0` 但列表为空，不能静默成功）
- `skippedRows > 0` 表示上游脏行/重复日期，Java 应记录（不静默）

### 错误结构

与 `docs/API.md` §1 一致，便于 Java 直接透传原因：

```json
{
  "timestamp": "2026-10-26T11:16:58Z",
  "status": 404,
  "code": "NO_DATA",
  "message": "上游未返回日K数据（贝特瑞(已切换)）",
  "details": null,
  "path": "/internal/v1/kline"
}
```

| HTTP | code | 触发条件 | Java 侧建议映射 |
|---|---|---|---|
| 400 | `VALIDATION_ERROR` | 参数不合法（secid 格式、limit 超限、adjust 未知） | 参数错误，不重试 |
| 400 | `NOT_SUPPORTED` | `period` 非 daily | 参数错误，不重试 |
| 404 | `NO_DATA` | 上游 `rc=100`（不识别代码）、`data=null`、`klines=[]`（北交所等） | `DATA_SOURCE_UNAVAILABLE`，区分"该标的无数据" |
| 502 | `UPSTREAM_ERROR` | 上游 HTTP 非 200、响应非 JSON、`rc≠0` | `DATA_SOURCE_UNAVAILABLE`，可重试 |
| 502 | `INVALID_UPSTREAM_DATA` | 全部行都无法解析 | `DATA_SOURCE_UNAVAILABLE`，需告警排查 |
| 504 | `UPSTREAM_TIMEOUT` | 上游超时（默认 8s） | `DATA_SOURCE_UNAVAILABLE`，可重试 |

## AI 助手接口（P2）

供 Java 代理调用（前端只跟 Java 说话）。**不连数据库**，全部业务数据通过 Java 的只读工具取。

### `GET /agent/v1/health`

```json
{"status":"UP","modelConfigured":false,"model":null,
 "tools":["positions","watchlist-quotes","alert-events","market-brief","recaps","diary-search"]}
```

`modelConfigured=false` 表示未配置模型，此时助手仍可用（见下方 `mode`）。**不回显 API Key，也不发探活请求。**

### `POST /agent/v1/assistant/ask`

请求：`{"question": "今天我的持仓发生了什么变化？"}`（≤ 500 字）

响应要点：

```json
{
  "question": "今天我的持仓发生了什么变化？",
  "mode": "rule_based",
  "answer": {"conclusion": "当前持有 1 只标的，合计市值 2,511.60 元…", "evidence": ["510300 …"], "limits": []},
  "toolCalls": [{"tool": "positions", "label": "查询持仓", "ok": true, "summary": "1 只持仓", "elapsedMs": 143}],
  "dataAsOf": "2026-10-27T01:26:25Z",
  "generatedAt": "2026-10-27T06:49:57Z",
  "model": null,
  "usage": null,
  "degraded": ["未配置模型（STOCKCAT_LLM_* 为空），以下内容由规则汇总生成，不是模型分析结果"],
  "elapsedMs": 144
}
```

- `mode=llm`：模型基于工具数据写结论；`mode=rule_based`：未配置模型或模型失败，由 `app/agent/composer.py` 把真实数据拼成结论。
- `degraded` 非空时前端必须显示——不能让规则汇总被误当成 AI 分析。
- `dataAsOf` 取所有工具里最新的观测时点；某个工具失败不会让整次请求失败，失败原因进 `answer.limits`。

### `POST /agent/v1/alerts/explain`

告警解释（P2 场景二），由 Java 组装数据后调用。

请求：`{"event": {…事件快照…}, "current": {…当前行情与指标…}}`，字段见 `docs/API.md` §11。

响应四段：`why`（为什么触发）/ `meaning`（这意味着什么）/ `current`（当前状态）/ `evidence`（数据依据），
外加 `limits`（数据限制）与 `snapshotComplete`。

**本服务不做任何指标计算**——`observedDetail` 里没有的指标就是没有，不会拿当前 K 线回算。
模型只能改写 `why` / `meaning`；`current` / `evidence` / `limits` 恒由 `app/agent/alert_explain.py` 模板生成。
快照不完整（`snapshotComplete=false`）时连 `why` 也不交模型，避免补出一个当时不存在的数字。

## Agent 配置

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `STOCKCAT_JAVA_BASE_URL` | `http://127.0.0.1:8081` | Java 服务地址（工具数据来源） |
| `STOCKCAT_LLM_BASE_URL` | 空 | OpenAI 兼容地址，如 `https://api.deepseek.com/v1` |
| `STOCKCAT_LLM_MODEL` | 空 | 模型名，如 `deepseek-chat` |
| `STOCKCAT_LLM_API_KEY` | 空 | key 只读环境变量，日志与接口都不回显 |
| `STOCKCAT_LLM_TIMEOUT_SECONDS` | `45` | 单次模型调用超时 |
| `STOCKCAT_AGENT_MAX_TOOL_CALLS` | `4` | 单次提问最多调用几个工具 |

三个 `LLM_*` 全填才启用模型；只填一半按未配置处理，避免半死的调用。

## 测试

```bash
.venv\Scripts\python -m pytest -q
```

- `tests/fixtures/*.json` 是 2026-10-26 收盘后从上游**真实录制**的响应，测试全程不联网
- 覆盖：字段映射（第 3 列是收盘价这个易错点）、复权→fqt 映射、limit 截尾、`beg` 窗口冗余、空 `klines`、`rc=100`、超时/连接失败、脏行计数、重复日期、负价保留；净值接口覆盖：字段映射（FSRQ/DWJZ/LJJZ/JZZZL）、**分页（pageSize 上限 20）**、空列表→404、`ErrCode≠0`→502、脏行/重复计数、fundCode/limit 校验

## 已实测的坑（详见 `docs/DATA_SOURCES.md`）

1. 上游 `lmt` 参数不生效（`lmt=5` 仍返回全量）→ 只能用 `beg/end` 限窗，再本地截尾
2. `rc=0` 不等于成功，`klines=[]` 真实存在（如北交所 `0.835185`）
3. 前复权长期历史会出现负价（茅台 2001 年 `-312.68`）→ 适配器不修正，交给 Java 判定
4. 盘中请求会带上**未收盘的当日 bar** → 同步任务必须在收盘后运行
5. 净值源 `lsjz` 的 **pageSize 上限 20**（传 30/50 也只回 20 行）→ 按 `pageIndex` 翻页，并在"没有新日期"时立刻停止
6. 净值源非法代码返回 `ErrCode=0` 且空列表 → 映射 `NO_DATA`，不静默成功
