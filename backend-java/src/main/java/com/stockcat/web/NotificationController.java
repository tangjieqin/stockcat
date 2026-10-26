package com.stockcat.web;

import com.stockcat.domain.NotificationRecord;
import com.stockcat.service.NotificationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 通知查询/重试/测试，对应 docs/API.md §5。
 */
@RestController
@RequestMapping("/api/v1")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    public record NotificationResponse(
            Long id,
            Long alertEventId,
            String channel,
            String status,
            String providerMessageId,
            Integer attemptCount,
            String lastErrorCode,
            String lastErrorMessage,
            java.time.OffsetDateTime createdAt,
            java.time.OffsetDateTime sentAt
    ) {
        static NotificationResponse from(NotificationRecord r) {
            return new NotificationResponse(r.getId(), r.getAlertEventId(), r.getChannel(), r.getStatus(),
                    r.getProviderMessageId(), r.getAttemptCount(), r.getLastErrorCode(),
                    r.getLastErrorMessage(), r.getCreatedAt(), r.getSentAt());
        }
    }

    @GetMapping("/notifications")
    public List<NotificationResponse> list(@RequestParam(required = false) String status,
                                           @RequestParam(defaultValue = "50") int limit) {
        return service.recent(status, limit).stream().map(NotificationResponse::from).toList();
    }

    @PostMapping("/notifications/{id}/retry")
    public NotificationResponse retry(@PathVariable Long id) {
        return NotificationResponse.from(service.retry(id));
    }

    @PostMapping("/notifications/test")
    public NotificationResponse test() {
        return NotificationResponse.from(service.testSend());
    }

    @GetMapping("/notifications/status")
    public Map<String, Object> status() {
        return Map.of("channel", "SERVERCHAN", "configured", service.channelConfigured());
    }
}
