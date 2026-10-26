# StockCat P1 开发计划（历史行情 / 技术指标 / 基金溢价 / 收益曲线）

- 状态：待确认（P0 已验收通过）
- 目标：在 P0 的"实时行情 → 告警 → 通知 → 持仓 → 复盘"闭环之上，补齐**历史维度**：日 K 入库、ta4j 指标、ETF/LOF 参考数据与溢价、收益曲线与持仓变化。
- 原则不变：数据源先验证再编码；缺失数据不补零、不编造；Java 是唯一业务写入方；AI/Python 不在告警关键路径上。

---

## 0. 现状盘点（写计划前实际核对过）

| 项 | 现状 |
|---|---|
| Java | Spring Boot 3.3.5 / Java 21 / MyBatis / Flyway；包结构 `alert / collector / common / domain / mapper / notify / quote / service / web` |
| 迁移 | V1（11 张表）、V2（通知关联可空）、V3（`cooldown_seconds`）；**无 K 线表、无基金参考表、无持仓快照表** |
| 指标 | `pom.xml` **无 ta4j 依赖**；ta4j 最新为 0.26.0（2026-10），0.22 起 API 改动较大 |
| Python | 只有 `app/main.py` 的 `/health`，`app/adapters/` 空目录，`requirements.txt` + pytest 骨架 |
| 前端 | 12 个视图（总览/监控/告警 4 页/自选/持仓/流水/复盘/日记/通知）；依赖仅 vue/router/pinia/axios，**无图表库** |
| 可复用 | 交易日历与交易时段判断、调度器防重入、`quote_snapshot` 的 `data_status`（VALID/STALE）语义、告警 evaluator 注册表 |

---

## 1. 六个关键决策（需要你拍板，未拍板前不进 P1.1）

**D1. 历史数据抓取放在 Python（遵循现有架构）**
Python 出 `/internal/v1/*` 标准化数据接口，Java 校验后落库。理由：与 ARCHITECTURE §3.3/§4 一致；P2 Agent 复用同一进程；抓取与解析不污染 Java 领域代码。
代价：本地开发要同时起两个进程。降级策略：Python 不可用 → Java 返回 `DATA_SOURCE_UNAVAILABLE`，历史/指标页面显示"数据源不可用"，**绝不用空数组冒充"没有数据"**。

> **P1.0 实测修订**：实时 IOPV 与最新净值改由 **Java** 新增 `TencentQuoteClient` 采集，不走 Python。原因：溢价告警在告警关键路径上，不能让 Python 进程成为单点。详见 `docs/DATA_SOURCES.md` §4。
>
> **用户确认（2026-10-26）**：**K 线抓取放 Python**，行情业务管理与技术指标计算放 Java。Python 侧只做抓取与字段映射，落库/校验/指标/告警全在 Java。

**D2. 指标不落库，按查询计算**
指标是 K 线的确定函数。落库会带来"K 线修正 → 指标失效"的一致性负担，而 250 根日 K 的 MACD/RSI/KDJ 计算量在毫秒级。
唯一例外：P1.6 的指标类告警，触发时把当时的指标值与 K 线日期写进 `alert_event`（沿用现有快照思路），保证事后可解释。

**D3. 先只做日 K，分钟 K 延后**
东财分钟 K 历史深度有限（通常只能取最近若干天），"看似有数据但无法回溯"比没有更糟。日 K 满足指标、收益曲线、复盘全部需求。分钟 K 待 P1.6 后按真实需求再评估。

**D4. 复权口径：以**前复权（qfq）**为指标基准，表结构按 `adjust` 维度预留**
`kline_daily` 唯一键含 `adjust`，先只写 `qfq`。前复权的历史值会随除权变化，因此同步必须 **UPSERT 覆盖近期 N 天**，不能只做增量 INSERT。

**D5. 溢价率口径与缺失处理**
`premium_rate = (market_price - iopv) / iopv × 100`（百分数值，与 API 约定一致）。
`nav`（净值）与 `iopv`（参考净值）**分开存、分别标注来源与时间**；IOPV 缺失时溢价率返回 `null` 并给出 `reason`，**禁止用 NAV 顶替 IOPV 算溢价**。ETF/LOF 才计算，股票返回"不适用"。

> **P1.0 实测修订**：实测 LOF **没有实时 IOPV**，只有 T-1 净值；若用净值当分母而不标注，会输出一个"看起来实时、其实滞后一天"的溢价率。因此返回值必须带 `premiumBasis`（`IOPV` / `NAV`）、`premiumAsOf`、`staleDays`，前端按 basis 显示不同文案。详见 `docs/DATA_SOURCES.md` §3.1。

**D6. 前端图表库：ECharts + vue-echarts**
需求是多 pane（K 线 + 成交量 + MACD/RSI/KDJ 副图）、中文文档、后续溢价曲线与收益曲线可复用。轻量级金融库（lightweight-charts）在副图指标与混合图表上会拖慢进度。本地单机应用，包体积可接受。

---

## 2. 任务分解

规模标注：**S**（半天级）/ **M**（1 天级）/ **L**（2 天级）。顺序即依赖顺序。

### P1.0 数据源验证与文档（S）— 先做，验收通过才继续

交付：
- 实测并记录：东财 `push2his` 日 K 接口（候选：`klt=101` 日 K、`fqt` 复权参数）的可用性、字段含义、单次最大条数、历史深度、限频表现。
- 实测并记录：ETF/LOF 的 IOPV、净值（NAV）字段/接口可得性、更新频率、盘中是否为实时值。
- 新增 `docs/DATA_SOURCES.md`：来源、字段映射、频率、复权、限频、已知坑、失效判断。

验收：
- 对 1 只股票 + 1 只 ETF 能取到**与东财网页数值一致**的日 K 与参考数据（抓 3 天截图级核对）。
- 结论明确写出：IOPV 是否可得（决定 P1.4 与溢价告警能否上线）。

> 风险：东财 IP 限频在 P0 已遇到过一次。验证阶段就对同一 IP 做小批量、带间隔的请求，并把"单次最多 N 只、间隔 ≥ X ms"写进文档。

### P1.1 Python 历史行情适配器（M）— ✅ 已完成 2026-10-26

实现与契约见 `backend-python/README.md`；错误码表、脏行计数、`skippedBars` 语义均已落地。

交付（已实现）：
- `app/adapters/eastmoney_history.py`：抓取 + 字段映射，**不落库、不判业务**。
- `app/api/kline.py`：`GET /internal/v1/kline?secid=&period=daily&adjust=qfq&limit=250&end=`
  - **入参用 `secid`**（`1.600519`），由 Java `SymbolMapping` 生成，Python 不掌握 A 股代码规则
  - `limit` 上限 500（前复权长期会出负价）；`period` 仅 daily，其他值明确 400
  - 响应含 `barCount`、`skippedBars`、`changePercent`、`turnoverRate`、`fetchedAt`
- `app/errors.py`：统一错误体，结构对齐 `docs/API.md` §1。
- 测试 27 项全过，全部用 2026-10-26 真实录制的响应（`tests/fixtures/`），**不联网**。

验收（已达成）：
- 空 `klines`、`rc=100`、超时、连接失败、脏行、重复日期、负价保留均有覆盖；不返回 `bars: []` 伪装成功。
- 真实调用核对：600519 三根 K 线与东财 push2 实时快照一致；510300 与 LOF 同样可取。

> 遗留：`end` 默认取 Asia/Shanghai 今天，盘中会带回未收盘的当日 bar——**由 Java 同步任务在收盘后运行 + 写前校验**，本层不据此过滤。

### P1.2 Java 历史行情落库与同步（M）

交付：
- 迁移 `V4__history_kline.sql`：

```
kline_daily(id, instrument_id FK, trade_date, adjust, open, high, low, close,
            volume, amount, source_code, fetched_at,
            UNIQUE(instrument_id, trade_date, adjust))
```

- `HistoryDataClient`（Java → Python，超时/重试/熔断式跳过，失败不影响 P0 采集与告警）。
- `HistorySyncJob`：收盘后（默认 15:30，复用交易日历判断）对 **自选 + 监控 + 持仓** 标的同步最近 N 天（默认 120，覆盖 250 需手动）。
- 手动同步 API：`POST /api/v1/instruments/{id}/history/sync`。
- 查询 API：`GET /api/v1/instruments/{id}/klines?adjust=qfq&limit=120&end=`。
- 同步结果统计（成功/失败/跳过标的数、耗时），沿用 P0 调度可观测性写法。

验收：
- 真实标的日 K 入库，抽 3 天与东财页面逐项核对（开高低收量）。
- 重复跑同步为幂等 UPSERT；除权后重跑能修正历史值。
- Python 停掉时：同步任务记录失败但不抛全局异常，P0 的实时采集与告警照常运行。

**已实现（2026-10-26，与上文的差异）**：
- 表名按决策 D3 定为 `kline_daily`，`adjust` 参与唯一键（`V4__kline_daily.sql`），并加了 `close > 0` / `high >= low` 的 DB 级兜底约束。
- 同步触发改成「交易日 15:35」而不是 15:30（`stockcat.history.sync.cron`），因为 15:30 偶发取到收盘竞价未落定的数据。
- 手动同步拆成两个：单标的 `POST /instruments/{id}/history/sync` 与全范围 `POST /history/sync`。
- 拒写规则落到代码：未收盘当日 K 线、非正价格、`high < low`；三项拒绝数在响应里逐个暴露，不静默丢弃。
- 代码位置：`history/HistoryDataClient`、`service/KlineSyncService`、`service/KlineQueryService`、`collector/KlineSyncScheduler`、`web/KlineController`。
- 实测：600519 / 510300 / 000063 各 120 根落库，抽 3 天与上游逐字段比对一致；重复同步行数不变（幂等）；Python 停机时返回 `FAILED + DATA_SOURCE_UNAVAILABLE` 且 `quotes/latest`、`klines` 查询不受影响。

### P1.3 指标计算与查询（M）— 已实现，但**弃用 ta4j**

原计划引 ta4j 算指标。实测后改为全部自研，原因有三条实测证据：

| 项 | ta4j 0.21.0 | 通达信口径 | 后果 |
|---|---|---|---|
| RSI(6)（600519 2026-10-26） | 62.19 | 75.47 | **差 13 个点**：ta4j 用 α=2/(N+1)，通达信用 α=1/N |
| KDJ | 无 J 指标，D 是 K 的简单均值 | K/D 用 SMA 递推，J = 3K−2D | 数值对不上 |
| MACD（同数据 120 根后 EMA26） | 偏 0.04 元 | — | DIF 相对偏差约 0.3% |

告警正是靠指标穿越触发，"差一点点"就会多推/少推一条通知，所以口径必须一致。
另外 ta4j 0.22+ 编译目标是 Java 25（本项目 21），不引自研也就不存在版本天花板。

实际交付（与原计划的差异）：

- `indicator/IndicatorCalculator`：MACD(12,26,9) + 调度，`indicator/RsiCalculator`：RSI(6,12,24)，`indicator/KdjCalculator`：KDJ(9,3,3)。
- API：`GET /api/v1/instruments/{id}/indicators?adjust=qfq&limit=250`。
  原计划的 `period`/`list` 参数没做——只支持日 K（D3），各指标参数固定；多传一个参数就多一条"参数组合不一致"的排查路径。
  数据不足返回 `null`（不是 0，也不是补零）+ `requiredBars` 说明。
- 口径文档写进 `docs/API.md` §3 与各计算器的类注释（未单开 `docs/INDICATORS.md`，避免同一份口径散落两处）。
- 验收：Python 独立实现（逐行重写通达信公式）对同一份真实数据交叉核对，误差阈值 1e-6 / 1e-4；
  另外用 510300、000063 两只标的各 120 根 × 9 个字段与接口逐点比对，**0 处差异**。
- 顺带修掉一个参数校验缺口：`@Max` 写在 `@RequestParam` 上抛的是 `ConstraintViolationException`，
  原先落到兜底返回 **500**（`limit=9999` 看起来像后端崩了）；现已统一为 `400 VALIDATION_ERROR`，`/klines` 一并修好。

### P1.4 基金参考数据与溢价（M）— 已实现（2026-10-26）

实现与差异（都跟着 DATA_SOURCES §3/§7 的实测走，不是拍脑袋）：

- **IOPV 与净值分开采**：IOPV + 价格由 Java `TencentFundClient` 直连腾讯（GBK 解码、idx78/81），
  随行情采集每 15 秒刷新（只对 ETF/LOF 发一次批量请求）；净值由 Java `FundNavClient` → Python
  `GET /internal/v1/fund-nav`（天天基金 lsjz，带 FSRQ 净值日期）。
  腾讯 idx81 的净值**不带日期**，白天分不清是 T 还是 T-1，因此只作参考、不参与溢价计算。
- **不用 idx77**：上游对 LOF 的溢价基准是 T-1 净值却无标注。溢价在 Java 现算并带
  `premiumBasis(IOPV/NAV)`、`premiumAsOf`、`navStaleDays`；缺基准时 `premiumRate=null`，不用别的数顶替。
- **分页坑**：lsjz 的 pageSize 上限 20（传 30/50 都只回 20 行，实测），Python 适配器按 pageIndex 翻页，
  并在"没有新日期"时立刻停止（防上游忽略 pageIndex 时死循环）。
- 表 `fund_reference`（V5）：一行 = 一个基金一个交易日；盘中 UPSERT `price/iopv`、晚间 UPSERT `nav/nav_date`，
  两条 UPSERT 用 COALESCE 互不覆盖；`market_source_code` / `nav_source_code` 分开记（一行两个来源）。
  原计划的 `premium_basis` 列取消——溢价不落库（依赖当下价格），basis 在查询层给出。
- 净值同步任务：每晚 21:30（实测 T 日净值当晚才公布，15:45 太早）+ 启动补跑（桌面端常不开机），
  每次拉最近 30 期，幂等 UPSERT 自然补齐缺口。
- API：`GET /instruments/{id}/fund-reference`（股票返回 `applicable=false` + reason，不是 404）、
  `GET /fund-references/latest`（批量，股票不在结果里）、`POST /instruments/{id}/fund-reference/refresh`
  （行情/净值两路结果分开报，`SUCCESS/PARTIAL/FAILED`）。
- 前端：自选列表"溢价"列（基金才有，带 `IOPV`/`净值(T-x)` 小标签与悬停明细）；详情页基金参考区块
  （溢价/IOPV/净值/价格 + 最近 10 日小表 + 刷新按钮）。
- 验收实测：510300（ETF）溢价 +0.0191%，基准 IOPV；临时插入 161725（LOF）验证后删除，
  溢价 −0.348%，基准 NAV（0.5459 = T 日净值）；与手算一致。Java 108 tests、Python 44 tests 全过。
- **PREMIUM_* 告警类型仍未做**：数据通路已通（`latestPremiums` 可直接喂给 EvaluationContext），
  放到 P1 收尾时与指标告警一起补，避免本次范围膨胀。

验收（原计划）：溢价率与手算一致 ✅；页面明确区分"实时 IOPV"与"昨日净值" ✅（净值带日期与滞后天数）。

### P1.5 收益曲线与持仓变化 + 溢价告警（L）— 已实现

交付：
- 迁移 `V6__position_snapshot.sql`：`position_snapshot(trade_date, instrument_id, quantity, avg_cost, cost_value, close_price, market_value, unrealized_pnl, realized_pnl_cum, price_adjust, source_code, created_at, UNIQUE(trade_date, instrument_id))`。
- `PositionSnapshotService`：按流水重放生成每日快照（复用 `HoldingComputationService.apply`，不复制摊薄成本公式），区间重建 = 先删后插；`PositionSnapshotJob` 收盘后 15:45 + 启动补跑。
- 组合层不建表：`GET /api/v1/portfolio/equity-curve`、`GET /api/v1/portfolio/position-changes`、`POST /api/v1/portfolio/snapshots/rebuild`。
- 溢价告警：`PREMIUM_ABOVE` / `PREMIUM_BELOW` 两个 ruleType（`EvaluationContext` 增持溢价视图，盘中 30 秒评估，只对 ETF/LOF 开放）。
- 前端：持仓页改为三页签（当前持仓 / 收益曲线 / 持仓变化），曲线用 ECharts 双 y 轴（金额 + 累计收益）。

实现时的关键偏差（均已在代码注释与文档中说明）：
- **收盘价必须不复权（`raw`）**。计划里没写口径，但 `avg_cost` 是真实成交价，前复权价随除权整体下移，
  两者相减会算出"每年亏一笔分红"的假曲线。因此快照任务会先补齐 `adjust=raw` 的日线（缺则调 Python 同步），
  并在行上记 `price_adjust` 便于事后解释。
- 缺价留 `null` + `complete=false`，不按 0 汇总；最后一天缺价时不给收益率。
- 区间内开仓又清仓时 `quantityDelta=0`，用 `closed` + `realizedPnlInRange` 表达"中间发生过交易"。

验收：
- 手算对齐（单测）：买入 100@10 费用 5 → 摊薄 10.05；卖 40@12 费用 2 → 累计已实现 76；清仓后累计 12；
  市值/浮盈逐日与手算一致（`PositionSnapshotServiceTest`）。
- 真实数据：510300 买入 1000@4.10（费用 5）+ 卖出 400@4.18（费用 2）→ 净投入 2435、期末市值 2507.4、
  总盈亏 72.4（= 浮盈 44.4 + 已实现 28）、简单收益率 2.97%，与"市值 − 净投入"完全一致。
- 口径限制写进 API `note` 与页面脚注：**未做资金加权（TWR），未调整分红/送股**，页面显示"简单收益"。

### P1.6 指标类告警扩展（M）— 已实现

复用 P0 的 `RuleEvaluator` 注册表（架构决策 10），落地 6 个类型：

| ruleType | params | 需要 K 线 |
|---|---|---|
| `MACD_GOLDEN_CROSS` / `MACD_DEATH_CROSS` | `{}` | 35 根 |
| `KDJ_GOLDEN_CROSS` / `KDJ_DEATH_CROSS` | `{}` | 10 根 |
| `RSI_CROSS_ABOVE` / `RSI_CROSS_BELOW` | `{"period":6,"threshold":80}` | period+2 根 |

与原计划的差异（都是主动选择，不是漏做）：

- **命名用具体类型而不是 `MACD_CROSS{direction}`/`RSI_ABOVE`**：与 P0 的 `PRICE_ABOVE/BELOW` 风格一致，
  规则列表里一眼能看懂；`RSI_ABOVE` 这个名字会让人以为是"超过就触发"，实际是穿越，改成 `RSI_CROSS_*` 避免歧义。
- **新增 `EvaluationContext`**：`RuleEvaluator.evaluate(params, quote)` 改为 `(params, context)`，
  行情类读 `context.quote()`，指标类读 `context.bars()/indicators()`。没有用两套接口 + instanceof 分支，
  是因为以后还会加溢价率规则，三类输入各写一条分支早晚会漏。
- **单独的收盘后任务 `IndicatorAlertJob`（15:40）**，不塞进盘中的 `AlertEvaluationJob`：
  盘中只有半根 K 线，用它将就判金叉会推出"收盘并不存在"的信号。行情类规则在盘中任务里遇到指标规则会直接跳过（有测试钉住）。
- **`PREMIUM_*` 没做**：依赖 P1.4 的 IOPV，等数据通路通了再补。

验收结果：
- 事件里 `observed_detail` 记录当根 K 线的指标值 + 交易日，`deduplication_key = rule-{id}-{交易日}`。
- 手动入口 `POST /alert-rules/indicator/evaluate` 逐条返回 `TRIGGERED / NOT_TRIGGERED /
  SKIPPED_INSUFFICIENT_BARS / SKIPPED_NO_KLINES / ERROR`，把"没触发"和"数据不够"分开。
- 实测（600519 真实 120 根）：KDJ 金叉与 RSI6 上穿 70 触发并成功推送 Server酱；
  MACD 金叉/死叉因已在同侧而不触发；同日重跑 0 触发（去重生效）。

### P1.7 前端图表与集成验收（M）— 已实现（2026-10-26）

> P1.4/P1.5 尚未做，因此本阶段实际交付为：**标的详情页（K 线 + 成交量 + MACD/RSI/KDJ）**，
> 溢价/净值区块与收益曲线页签待对应数据通路完成后补充。

实现与差异：

- 依赖定为 **echarts 6 + vue-echarts 8**（D6），按需注册（candlestick/line/bar + grid/tooltip/dataZoom/title/legend/markLine + CanvasRenderer），不整包引入；详情页 chunk 约 630KB（gzip 213KB），仅路由懒加载时下载。
- 图表装配独立成 `src/charts/klineChart.ts`（纯函数 `buildKlineOption`），视图只管取数与状态；指标与 K 线按日期对齐（后端本就同下标同日期，这里再兜一次底）。
- 五个 pane 共用 x 轴与 dataZoom（滚轮 + 滑块），RSI 固定 0~100 轴并画 20/50/80 参考线；KDJ 的 J 不裁剪（轴自适应），与看盘软件一致。
- 状态全覆盖：加载中 / 未同步（count=0，说明"是没同步不是没行情"）/ 数据源不可用（同步返回 `DATA_SOURCE_UNAVAILABLE` 时点名 Python 服务）/ 数据不足（副图标题追加"需 N 根"）/ 最后同步超 24 小时提示可能过期 / 标的不存在（404 页）。
- 新增后端小接口 `GET /api/v1/instruments/{id}`（详情页按 id 直开，404 明确返回），单测 2 例；Java 83 tests 全过、`vue-tsc` 零错误。
- 真实验收：无头 Chrome 截图核对 600519 / 510300（图表、行情条、图例、脚注）、00063（未同步引导，不画假图）、id=9999（404 页）；代理用 `STOCKCAT_API_TARGET` 指向 8082 新实例，未动用户常驻的 8080/5173。
- 明确不做：前端不算任何指标（口径唯一来源是 Java，避免两处实现漂移）；K 线不叠加前端自算 MA（同因）。

验收（原计划）：
- 从自选点进详情 → 看到真实 K 线与指标 → 切标的 → 溢价/净值区块 → 持仓页看收益曲线，全链路无假数据。（前三项已达成；溢价/收益曲线随 P1.4/P1.5 补）
- Java 单测 + Python pytest + `vue-tsc` 全绿。（已达成）

---

## 3. 新增数据模型一览

| 表 | 关键点 |
|---|---|
| `kline_daily` | 唯一键含 `adjust`；UPSERT；`source_code` 可追溯 |
| `fund_reference` | `nav` 与 `iopv` 分列、分时间；按交易日一条 |
| `position_snapshot` | 由流水重放生成，可全量重建；组合指标靠聚合，不另建表 |

## 4. 主要风险与对策

| 风险 | 对策 |
|---|---|
| 东财限频 / 封 IP | 批量小、带间隔、夜间跑；单次同步标的数上限可配置；失败标的次日补 |
| IOPV 数据不可得或延迟大 | P1.0 先定结论；不可得就降级为净值展示，不上溢价告警，不编造"实时溢价" |
| ~~ta4j 版本 API 变动~~ | 已消解：P1.3 改为自研实现，不再引第三方指标库 |
| KDJ 无标准内置实现 | 已解决：自研 `KdjCalculator` + 手算样例 + 与独立实现逐值核对（容差 1e-4） |
| 前复权历史值随除权变动 | 同步窗口内强制覆盖更新，不做纯增量 |
| 项目范围膨胀 | P1 只做日 K；分钟 K、TWR、分红调整、多市场全部押后，写进"不做清单" |

## 5. 明确不做（P1 边界）

- 分钟 K / 周 K / 月 K、盘后复权（hfq）
- 真实资金加权收益率（TWR）、分红送股自动调整、现金账户管理
- 港股/美股/北交所、多用户、公网部署
- 任何"买卖建议"式结论展示

## 6. 建议的执行顺序

1. **P1.0 数据源验证**（不写业务代码，先出结论，这一步决定后面两件事能不能做）
2. P1.1 → P1.2（打通"取数 → 落库 → 可查"，此时就能在 UI 上看到真实 K 线数据）
3. P1.3（指标）与 P1.7 的图表骨架可并行
4. P1.4（基金）→ P1.5（收益曲线）
5. P1.6（指标告警）收尾

每步结束：跑测试 → 检查 diff → commit → 同步 `API.md`/`DATABASE.md`。
