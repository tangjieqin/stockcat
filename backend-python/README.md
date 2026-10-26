# StockCat Python 后端

FastAPI 服务，当前承担 **P1 历史行情适配**；P2 将承接 AI 复盘 Agent（LangChain）。

**职责边界（重要）**：本服务只做「抓取 + 字段映射 + 错误翻译」，不连业务数据库、不写业务表、不做业务取舍。
复权是否可用于指标、非正值 bar 是否入库、当日未收盘 bar 是否拒写——**全部由 Java 判定**。

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
