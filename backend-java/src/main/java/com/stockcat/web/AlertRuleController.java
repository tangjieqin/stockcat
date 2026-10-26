package com.stockcat.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcat.domain.AlertRule;
import com.stockcat.service.AlertRuleService;
import com.stockcat.service.IndicatorAlertService;
import com.stockcat.web.dto.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * 告警规则 CRUD + 批量创建，对应 docs/API.md §4。
 */
@RestController
@RequestMapping("/api/v1")
public class AlertRuleController {

    private final AlertRuleService service;
    private final IndicatorAlertService indicatorAlertService;
    private final ObjectMapper objectMapper;

    public AlertRuleController(AlertRuleService service, IndicatorAlertService indicatorAlertService,
                               ObjectMapper objectMapper) {
        this.service = service;
        this.indicatorAlertService = indicatorAlertService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/alert-rules")
    public PageResponse<AlertRuleResponse> list(
            @RequestParam(required = false) Long instrumentId,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<AlertRule> rules = service.list(instrumentId, enabled, page, size);
        return new PageResponse<>(rules.items().stream().map(AlertRuleResponse::from).toList(),
                rules.page(), rules.size(), rules.total());
    }

    @PostMapping("/alert-rules")
    public ResponseEntity<AlertRuleResponse> create(@Valid @RequestBody CreateAlertRuleRequest req) {
        AlertRule created = service.create(req.name(), req.instrumentId(), req.ruleType(),
                toJson(req.params()), req.cooldownSeconds(), req.enabled());
        return ResponseEntity.created(URI.create("/api/v1/alert-rules/" + created.getId()))
                .body(AlertRuleResponse.from(created));
    }

    public record BatchRequest(@NotEmpty List<Long> instrumentIds, @NotBlank String name,
                               @NotBlank String ruleType, java.util.Map<String, Object> params,
                               Integer cooldownSeconds, Boolean enabled) {
    }

    @PostMapping("/alert-rules/batch")
    public AlertRuleService.BatchResult createBatch(@Valid @RequestBody BatchRequest req) {
        return service.createBatch(req.instrumentIds(), req.name(), req.ruleType(),
                toJson(req.params()), req.cooldownSeconds(), req.enabled());
    }

    @PatchMapping("/alert-rules/{id}")
    public AlertRuleResponse update(@PathVariable Long id, @Valid @RequestBody UpdateAlertRuleRequest req) {
        // params 为 null 表示本次不修改参数（PATCH 语义），不能序列化成 "{}" 触发参数校验
        String paramsJson = req.params() == null ? null : toJson(req.params());
        return AlertRuleResponse.from(service.update(id, req.name(), req.ruleType(), paramsJson,
                req.cooldownSeconds(), req.enabled()));
    }

    @DeleteMapping("/alert-rules/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 手动触发指标类规则评估（P1.6）。收盘后任务在 15:40 自动跑；这个入口用于补跑与验证：
     * 不用等到收盘，也不用改系统时间。
     *
     * @param instrumentId 只评估某只标的；不传则评估全部启用中的指标类规则
     */
    @PostMapping("/alert-rules/indicator/evaluate")
    public IndicatorAlertSummary evaluateIndicatorRules(
            @RequestParam(required = false) Long instrumentId) {
        return indicatorAlertService.evaluate(instrumentId);
    }

    private String toJson(java.util.Map<String, Object> params) {
        try {
            return objectMapper.writeValueAsString(params == null ? java.util.Map.of() : params);
        } catch (Exception e) {
            throw com.stockcat.common.ApiException.validationError("params 序列化失败");
        }
    }
}
