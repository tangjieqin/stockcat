-- V7__watchlist_group.sql: 自选自定义分组（用户需求：自选列表顶部按分组页签切换）
-- group_name 为 NULL 表示默认「自选」；一个标的同时只属于一个分组（沿用 uq_watchlist_instrument 唯一约束）。
-- 分组本身不需要独立表：分组列表 = DISTINCT group_name，空分组随最后一个成员移出/改名自然消失。

ALTER TABLE watchlist_item
    ADD COLUMN group_name VARCHAR(64);
