package com.stockcat.web;

import com.stockcat.service.PositionService;
import com.stockcat.web.dto.PositionResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 当前持仓，对应 docs/API.md §6 GET /positions。
 */
@RestController
@RequestMapping("/api/v1")
public class PositionController {

    private final PositionService service;

    public PositionController(PositionService service) {
        this.service = service;
    }

    @GetMapping("/positions")
    public List<PositionResponse> positions() {
        return service.list();
    }
}
