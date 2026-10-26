package com.stockcat.service;

import com.stockcat.domain.AlertEvent;
import com.stockcat.domain.NotificationRecord;
import com.stockcat.mapper.AlertEventMapper;
import com.stockcat.mapper.NotificationRecordMapper;
import com.stockcat.notify.ServerChanClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 通知链路（docs/API.md §5 / DEVELOPMENT_PLAN P0.6）：
 * - 告警事件触发后创建 PENDING 记录并发送（SERVERCHAN 渠道）
 * - 失败记录 last_error（不含密钥）；只允许对 FAILED 记录重试；绝不重复发送已成功的通知
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final String CHANNEL = "SERVERCHAN";

    private final NotificationRecordMapper recordMapper;
    private final AlertEventMapper eventMapper;
    private final ServerChanClient serverChan;

    public NotificationService(NotificationRecordMapper recordMapper,
                               AlertEventMapper eventMapper,
                               ServerChanClient serverChan) {
        this.recordMapper = recordMapper;
        this.eventMapper = eventMapper;
        this.serverChan = serverChan;
    }

    /** 告警事件 -> 发送通知。任何失败都不影响事件本身（事件已落库）。 */
    public NotificationRecord dispatch(AlertEvent event) {
        NotificationRecord record = new NotificationRecord();
        record.setAlertEventId(event.getId());
        record.setChannel(CHANNEL);
        record.setStatus("PENDING");
        record.setAttemptCount(0);
        recordMapper.insert(record);

        return send(record, event);
    }

    /** 手动重试：只允许 FAILED（docs/API.md §5，避免重复发送已成功通知）。 */
    public NotificationRecord retry(Long recordId) {
        NotificationRecord record = recordMapper.findById(recordId);
        if (record == null) {
            throw com.stockcat.common.ApiException.notFound("通知记录不存在: id=" + recordId);
        }
        if (!"FAILED".equals(record.getStatus())) {
            throw com.stockcat.common.ApiException.conflict(
                    "只允许重试失败的通知（当前状态: " + record.getStatus() + "），避免重复发送");
        }
        AlertEvent event = eventMapper.findById(record.getAlertEventId());
        if (event == null) {
            throw com.stockcat.common.ApiException.notFound("关联事件不存在");
        }
        return send(record, event);
    }

    /** 渠道测试发送（通知配置页使用）。 */
    public NotificationRecord testSend() {
        NotificationRecord record = new NotificationRecord();
        record.setAlertEventId(null);
        record.setChannel(CHANNEL);
        record.setStatus("PENDING");
        record.setAttemptCount(0);
        recordMapper.insert(record);
        try {
            String pushId = serverChan.send("StockCat 通知测试",
                    "这是一条测试通知。\n\n收到即说明 Server酱 渠道配置正确。");
            record.setStatus("SENT");
            record.setProviderMessageId(pushId);
            record.setAttemptCount(1);
            record.setSentAt(OffsetDateTime.now());
            update(record);
        } catch (ServerChanClient.NotifierException e) {
            record.setStatus("FAILED");
            record.setAttemptCount(1);
            record.setLastErrorCode(e.getCode());
            record.setLastErrorMessage(safe(e.getMessage()));
            update(record);
        }
        return recordMapper.findById(record.getId());
    }

    private NotificationRecord send(NotificationRecord record, AlertEvent event) {
        String title = "告警: " + event.getRuleNameSnapshot() + " (" + event.getSymbol() + ")";
        StringBuilder desp = new StringBuilder();
        desp.append("**规则**: ").append(event.getRuleNameSnapshot()).append("\n");
        desp.append("**类型**: ").append(event.getRuleTypeSnapshot()).append("\n");
        desp.append("**条件**: `").append(event.getConditionParamsSnapshot()).append("`\n");
        if (event.getObservedPrice() != null) {
            desp.append("**触发价**: ").append(event.getObservedPrice()).append("\n");
        }
        if (event.getObservedChangePercent() != null) {
            desp.append("**涨跌幅**: ").append(event.getObservedChangePercent()).append("%\n");
        }
        desp.append("**触发时间**: ").append(event.getTriggeredAt()).append("\n");

        try {
            String pushId = serverChan.send(title, desp.toString());
            record.setStatus("SENT");
            record.setProviderMessageId(pushId);
            record.setAttemptCount(record.getAttemptCount() + 1);
            record.setLastErrorCode(null);
            record.setLastErrorMessage(null);
            record.setSentAt(OffsetDateTime.now());
            log.info("通知已发送: event={} record={}", event.getId(), record.getId());
        } catch (ServerChanClient.NotifierException e) {
            record.setStatus("FAILED");
            record.setAttemptCount(record.getAttemptCount() + 1);
            record.setLastErrorCode(e.getCode());
            record.setLastErrorMessage(safe(e.getMessage()));
            log.warn("通知发送失败: event={} code={}", event.getId(), e.getCode());
        }
        update(record);
        return recordMapper.findById(record.getId());
    }

    private void update(NotificationRecord record) {
        recordMapper.updateResult(record.getId(), record.getStatus(), record.getProviderMessageId(),
                record.getAttemptCount(), record.getLastErrorCode(), record.getLastErrorMessage(),
                record.getSentAt());
    }

    /** 错误信息截断且不含密钥（密钥不进日志/消息） */
    private static String safe(String message) {
        if (message == null) return null;
        String m = message.replaceFirst("(SCT|sct)[A-Za-z0-9]+", "***");
        return m.length() > 500 ? m.substring(0, 500) : m;
    }

    public List<NotificationRecord> byEvent(Long eventId) {
        return recordMapper.selectByEventId(eventId);
    }

    public List<NotificationRecord> recent(String status, int limit) {
        return recordMapper.selectRecent(status, limit);
    }

    public boolean channelConfigured() {
        return serverChan.configured();
    }
}
