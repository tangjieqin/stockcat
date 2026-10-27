package com.stockcat.web;

import com.stockcat.service.AgentToolService;
import com.stockcat.web.dto.AgentToolDtos.*;
import com.stockcat.web.dto.AgentToolResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * P2.1 Agent 受控只读工具 API（docs/PLAN_P2.md §2、docs/API.md §10）。
 *
 * <p><b>这个 Controller 只允许出现 @GetMapping</b>：它是 Python Agent 唯一的数据入口，
 * 一旦混进写接口，"AI 只读"的承诺就作废了。{@code AgentToolControllerTest} 用反射锁死这条规则。
 */
@RestController
@RequestMapping("/api/v1/agent/tools")
public class AgentToolController {

    private final AgentToolService service;

    public AgentToolController(AgentToolService service) {
        this.service = service;
    }

    @GetMapping("/watchlist-quotes")
    public AgentToolResponse<WatchlistQuotes> watchlistQuotes() {
        return service.watchlistQuotes();
    }

    @GetMapping("/positions")
    public AgentToolResponse<Positions> positions() {
        return service.positions();
    }

    @GetMapping("/alert-events")
    public AgentToolResponse<AlertEvents> alertEvents(@RequestParam(defaultValue = "10") int limit) {
        return service.alertEvents(limit);
    }

    /** date 只表示复盘归属日期；指数行情是最新采集快照，不回补历史。 */
    @GetMapping("/market-brief")
    public AgentToolResponse<MarketBrief> marketBrief(@RequestParam(required = false) LocalDate date) {
        return service.marketBrief(date);
    }

    @GetMapping("/recaps")
    public AgentToolResponse<Recaps> recaps(@RequestParam(required = false) LocalDate from,
                                            @RequestParam(required = false) LocalDate to,
                                            @RequestParam(defaultValue = "5") int limit) {
        return service.recaps(from, to, limit);
    }

    @GetMapping("/diary/search")
    public AgentToolResponse<DiarySearch> diarySearch(@RequestParam String keyword,
                                                      @RequestParam(defaultValue = "5") int limit) {
        return service.diarySearch(keyword, limit);
    }

    /** 资料库片段检索（P3.3）：命中片段带 citation，模型只能引用服务端拼好的出处。 */
    @GetMapping("/library/search")
    public AgentToolResponse<LibrarySearch> librarySearch(@RequestParam String keyword,
                                                          @RequestParam(defaultValue = "5") int limit) {
        return service.librarySearch(keyword, limit);
    }
}
