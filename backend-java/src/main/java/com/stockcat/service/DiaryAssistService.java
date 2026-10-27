package com.stockcat.service;

import com.stockcat.assistant.AssistantClient;
import com.stockcat.common.ApiException;
import com.stockcat.domain.DiaryNode;
import com.stockcat.web.dto.PositionResponse;
import com.stockcat.web.dto.WatchlistItemResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 日记 AI 辅助（P2 场景四）：整理单篇、按关键词回顾。
 *
 * <p>职责边界：本类只做**组装与转发**——把正文、标题和「已知标的」白名单送给 Python，
 * 把结果原样带回给前端。**不落库**：整理结果写不写回正文，由用户在编辑器里点保存决定。
 * 这与复盘草稿是同一条规矩：AI 不改用户已经写下的东西。
 *
 * <p>为什么白名单在 Java 侧组装：Python 不连数据库（P2 架构约束），
 * 而"这个标的到底存不存在"只有库知道。把自选 + 持仓送过去，模型越界给出的代码会被丢弃。
 */
@Service
public class DiaryAssistService {

    /** 正文硬上限：超过这个长度说明用户写的是长文，先让人自己精简，比悄悄截断诚实。 */
    private static final int MAX_BODY_CHARS = 20_000;
    private static final int DEFAULT_REVIEW_LIMIT = 5;
    private static final int MAX_REVIEW_LIMIT = 10;

    private final WatchlistService watchlistService;
    private final PositionService positionService;
    private final DiaryService diaryService;
    private final AssistantClient assistantClient;

    public DiaryAssistService(WatchlistService watchlistService,
                              PositionService positionService,
                              DiaryService diaryService,
                              AssistantClient assistantClient) {
        this.watchlistService = watchlistService;
        this.positionService = positionService;
        this.diaryService = diaryService;
        this.assistantClient = assistantClient;
    }

    /**
     * 整理一篇日记。{@code nodeId} 有值时以库里的标题/正文兜底，
     * 但前端传来的内容优先——用户在编辑器里改了还没保存时，整理的就该是眼前这一版。
     */
    public Map<String, Object> refine(Long nodeId, String title, String content) {
        String body = content;
        String subject = title;

        DiaryNode node = null;
        if (nodeId != null) {
            node = diaryService.get(nodeId);
            if (body == null || body.isBlank()) {
                body = node.getContent();
            }
            if (subject == null || subject.isBlank()) {
                subject = node.getTitle();
            }
        }

        if (body == null || body.isBlank()) {
            throw ApiException.validationError("正文为空，没有可整理的内容");
        }
        if (body.length() > MAX_BODY_CHARS) {
            throw ApiException.validationError(
                    "正文超过 " + MAX_BODY_CHARS + " 字，请先自行精简再整理（AI 不做长文压缩）");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", subject == null ? "" : subject);
        payload.put("content", body);
        payload.put("knownInstruments", knownInstruments());
        return assistantClient.refineDiary(payload);
    }

    /** 按关键词回顾日记。关键词必填——不带词的"总结我的日记"等于让模型自由发挥。 */
    public Map<String, Object> review(String keyword, Integer limit) {
        if (keyword == null || keyword.isBlank()) {
            throw ApiException.validationError("keyword 必填：日记回顾需要一个检索词");
        }
        int effective = limit == null || limit <= 0
                ? DEFAULT_REVIEW_LIMIT
                : Math.min(limit, MAX_REVIEW_LIMIT);
        return assistantClient.reviewDiary(keyword.trim(), effective);
    }

    /**
     * 已知标的白名单：自选在前，持仓补充（同一标的只出现一次）。
     *
     * <p>顺序有意义：自选是用户主动关注的，优先占据模型的注意力。
     */
    List<Map<String, Object>> knownInstruments() {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();

        for (WatchlistItemResponse row : watchlistService.list()) {
            var ins = row.instrument();
            if (ins == null || ins.symbol() == null || !seen.add(ins.symbol())) {
                continue;
            }
            out.add(item(ins.symbol(), ins.name()));
        }
        for (PositionResponse p : positionService.list()) {
            if (p.symbol() == null || !seen.add(p.symbol())) {
                continue;
            }
            out.add(item(p.symbol(), p.name()));
        }
        return out;
    }

    private static Map<String, Object> item(String symbol, String name) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("symbol", symbol);
        map.put("name", name);
        return map;
    }
}
