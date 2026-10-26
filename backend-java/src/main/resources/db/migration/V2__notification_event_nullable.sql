-- V2: 测试发送的通知记录不关联告警事件（docs/API.md §5 testSend 语义），放开非空约束
ALTER TABLE notification_record ALTER COLUMN alert_event_id DROP NOT NULL;
