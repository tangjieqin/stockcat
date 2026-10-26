package com.stockcat.web;

import com.stockcat.service.IndexService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内置指数行情（配置驱动，不占用 instrument 表）。P0.9 首页将使用本接口。
 */
@RestController
@RequestMapping("/api/v1")
public class IndexController {

    private final IndexService indexService;

    public IndexController(IndexService indexService) {
        this.indexService = indexService;
    }

    @GetMapping("/indices")
    public List<IndexService.IndexQuote> indices() {
        return indexService.listLatest();
    }
}
