package com.stockcat.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcat.service.DiaryService;
import com.stockcat.web.dto.RecapDtos.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 我的日记（树状笔记本），对应 docs/API.md §7。
 */
@RestController
@RequestMapping("/api/v1")
public class DiaryController {

    private final DiaryService service;
    private final ObjectMapper objectMapper;

    public DiaryController(DiaryService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/diary/tree")
    public List<DiaryNodeView> tree() {
        return service.tree().stream().map(DiaryController::toView).toList();
    }

    @GetMapping("/diary/nodes/{id}")
    public DiaryNodeDetail get(@PathVariable Long id) {
        return toDetail(service.get(id));
    }

    @PostMapping("/diary/nodes")
    public DiaryNodeDetail create(@RequestBody CreateDiaryNodeRequest req) {
        var created = service.create(req.parentId(), req.nodeType(), req.title(), req.content(),
                toJson(req.relatedInstrumentIds()));
        return toDetail(created);
    }

    @PatchMapping("/diary/nodes/{id}")
    public DiaryNodeDetail update(@PathVariable Long id, @RequestBody UpdateDiaryNodeRequest req) {
        boolean moving = req.parentId() != null || req.sortOrder() != null;
        var updated = service.update(id, req.title(), req.content(),
                toJson(req.relatedInstrumentIds()), req.parentId(), req.sortOrder(), moving);
        return toDetail(updated);
    }

    @DeleteMapping("/diary/nodes/{id}")
    public DeleteResult delete(@PathVariable Long id,
                               @RequestParam(defaultValue = "false") boolean confirm) {
        long docCount = service.delete(id, confirm);
        return new DeleteResult(docCount);
    }

    @GetMapping("/diary/nodes")
    public List<DiarySearchHit> search(@RequestParam String keyword,
                                       @RequestParam(defaultValue = "20") int limit) {
        return service.search(keyword, limit).stream()
                .map(h -> new DiarySearchHit(h.id(), h.title(), h.path()))
                .toList();
    }

    // ---- 转换 ----

    private static DiaryNodeView toView(DiaryService.DiaryNodeView v) {
        return new DiaryNodeView(v.id(), v.parentId(), v.nodeType(), v.title(), v.sortOrder(),
                v.children().stream().map(DiaryController::toView).toList());
    }

    private DiaryNodeDetail toDetail(com.stockcat.domain.DiaryNode n) {
        Object related = null;
        if (n.getRelatedInstrumentIds() != null && !n.getRelatedInstrumentIds().isBlank()) {
            try {
                related = objectMapper.readTree(n.getRelatedInstrumentIds());
            } catch (Exception ignored) {
                related = n.getRelatedInstrumentIds();
            }
        }
        return new DiaryNodeDetail(n.getId(), n.getParentId(), n.getNodeType(), n.getTitle(),
                n.getContent(), related, n.getSortOrder(), n.getUpdatedAt());
    }

    private String toJson(List<Long> ids) {
        if (ids == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            throw com.stockcat.common.ApiException.validationError("relatedInstrumentIds 序列化失败");
        }
    }
}
