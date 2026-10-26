package com.stockcat.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcat.domain.AlertRule;
import com.stockcat.service.AlertRuleService;
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
    private final ObjectMapper objectMapper;

    public AlertRuleController(AlertRuleService service, ObjectMapper objectMapper) {
        this.service = service;
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
                toJson(req.params()), req.cooldownMinutes(), req.enabled());
        return ResponseEntity.created(URI.create("/api/v1/alert-rules/" + created.getId()))
                .body(AlertRuleResponse.from(created));
    }

    public record BatchRequest(@NotEmpty List<Long> instrumentIds, @NotBlank String name,
                               @NotBlank String ruleType, java.util.Map<String, Object> params,
                               Integer cooldownMinutes, Boolean enabled) {
    }

    @PostMapping("/alert-rules/batch")
    public AlertRuleService.BatchResult createBatch(@Valid @RequestBody BatchRequest req) {
        return service.createBatch(req.instrumentIds(), req.name(), req.ruleType(),
                toJson(req.params()), req.cooldownMinutes(), req.enabled());
    }

    @PatchMapping("/alert-rules/{id}")
    public AlertRuleResponse update(@PathVariable Long id, @Valid @RequestBody UpdateAlertRuleRequest req) {
        // params 为 null 表示本次不修改参数（PATCH 语义），不能序列化成 "{}" 触发参数校验
        String paramsJson = req.params() == null ? null : toJson(req.params());
        return AlertRuleResponse.from(service.update(id, req.name(), paramsJson, req.cooldownMinutes(), req.enabled()));
    }

    @DeleteMapping("/alert-rules/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    private String toJson(java.util.Map<String, Object> params) {
        try {
            return objectMapper.writeValueAsString(params == null ? java.util.Map.of() : params);
        } catch (Exception e) {
            throw com.stockcat.common.ApiException.validationError("params 序列化失败");
        }
    }
}
