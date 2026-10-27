package com.stockcat.web;

import com.stockcat.mapper.AlertEventMapper;
import com.stockcat.service.AlertExplainService;
import com.stockcat.service.NotificationService;
import com.stockcat.web.dto.AlertEventResponse;
import com.stockcat.web.dto.PageResponse;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 告警事件查询，对应 docs/API.md §5（默认按 triggeredAt 倒序，含通知记录）。
 */
@RestController
@RequestMapping("/api/v1")
public class AlertEventController {

    private final AlertEventMapper eventMapper;
    private final NotificationService notificationService;
    private final AlertExplainService explainService;

    public AlertEventController(AlertEventMapper eventMapper, NotificationService notificationService,
                                AlertExplainService explainService) {
        this.eventMapper = eventMapper;
        this.notificationService = notificationService;
        this.explainService = explainService;
    }

    @GetMapping("/alert-events")
    public PageResponse<AlertEventResponse> list(
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(required = false) Long instrumentId,
            @RequestParam(required = false) Long ruleId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<com.stockcat.domain.AlertEvent> rows =
                eventMapper.selectPage(instrumentId, ruleId, from, to, page * size, size);
        long total = eventMapper.countPage(instrumentId, ruleId, from, to);
        List<AlertEventResponse> items = rows.stream()
                .map(e -> AlertEventResponse.from(e, notificationService.byEvent(e.getId())))
                .toList();
        return new PageResponse<>(items, page, size, total);
    }

    @GetMapping("/alert-events/{id}")
    public AlertEventResponse detail(@PathVariable Long id) {
        com.stockcat.domain.AlertEvent event = eventMapper.findById(id);
        if (event == null) {
            throw com.stockcat.common.ApiException.notFound("事件不存在: id=" + id);
        }
        return AlertEventResponse.from(event, notificationService.byEvent(id));
    }

    /**
     * AI 解释一条已触发的事件（P2 场景二）。**只读**：不写库、不改规则、不发通知。
     *
     * <p>返回体形如 docs/API.md §11 的 AgentAlertExplainResponse：why / meaning / current /
     * evidence / limits。AI 不可用时返回 {@code DATA_SOURCE_UNAVAILABLE}，事件本身仍可正常查看。
     */
    @PostMapping("/alert-events/{id}/explain")
    public Map<String, Object> explain(@PathVariable Long id) {
        return explainService.explain(id);
    }
}
