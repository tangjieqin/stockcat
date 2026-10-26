-- V3: 冷却时间存储单位从「分钟」细化为「秒」，支持前端按秒/分钟/小时选择。
-- 存量数据按 *60 换算，语义不变。
ALTER TABLE alert_rule ADD COLUMN cooldown_seconds INTEGER NOT NULL DEFAULT 1800;
UPDATE alert_rule SET cooldown_seconds = cooldown_minutes * 60;
ALTER TABLE alert_rule DROP COLUMN cooldown_minutes;
