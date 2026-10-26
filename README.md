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
```

环境变量（均可用默认值）：`STOCKCAT_DB_HOST/PORT/NAME/USER/PASSWORD`，见 `.env.example`。通知推送：在 `.env` 中配置 `stockcat.notify.serverchan.send-key=<你的SendKey>`（sct.ftqq.com 免费申请，推送到微信「方糖」服务号），在「设置-通知」页可查看渠道状态并发送测试通知。

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

## 排障（FAQ）

| 现象 | 排查 |
|---|---|
| Java 启动报连接数据库失败 | 确认 `docker compose up -d` 已跑、容器 healthy（`docker ps`） |
| Flyway 校验失败 | 不要修改已应用的迁移文件；新变更新增 `Vn__xxx.sql` |
| 前端请求 404/代理失败 | 确认 Java 已启动（8080）；Vite 代理 `/api → 8080` 见 `vite.config.ts` |
| 页面价格显示 `—` | 行情缺失或过期（不按 0 显示）；点「刷新行情」或等调度器（交易时段 09:25-11:35/12:55-15:10） |
| 数据显示「数据过期」 | 行情时间距今超过 `stockcat.quote.stale-after-minutes`（默认 30 分钟），周末/节假日属正常 |
| 告警没触发 | 规则需启用；仅交易时段评估；仅 VALID 行情触发；检查是否在冷却期（默认 30 分钟） |
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

