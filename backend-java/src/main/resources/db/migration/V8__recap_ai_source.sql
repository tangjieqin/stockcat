-- V8：AI 复盘草稿的来源信息（P2.3 / P2.4）
--
-- 为什么要把「谁生成的、什么时候生成的、数据截至何时、有哪些限制」落库：
-- 草稿一旦被用户确认就会变成 MANUAL 正式复盘，几周后回看时，
-- 只有正文而没有生成时间与数据范围，就分不清"当时写的事实"和"事后补的印象"。
-- 这四列不参与业务计算，只作为溯源信息随复盘一起保存。
--
-- 注意：AI_DRAFT 被用户编辑确认后转为 MANUAL 时，这四列**保留**——
-- 它记录的是"这份复盘最初由 AI 起草、数据截至何时"，不是"当前状态"。

ALTER TABLE market_recap
    ADD COLUMN ai_generated_at TIMESTAMPTZ,
    ADD COLUMN ai_model        VARCHAR(64),
    ADD COLUMN ai_data_as_of   TIMESTAMPTZ,
    ADD COLUMN ai_limitations  JSONB;

COMMENT ON COLUMN market_recap.ai_generated_at IS 'AI 草稿生成时间；手工创建为 NULL';
COMMENT ON COLUMN market_recap.ai_model IS '生成草稿的模型名；规则降级生成时为 NULL';
COMMENT ON COLUMN market_recap.ai_data_as_of IS '草稿所用数据的观测时点（行情采集时间）';
COMMENT ON COLUMN market_recap.ai_limitations IS '生成时的数据限制清单（字符串数组）';
