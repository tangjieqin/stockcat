package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.domain.DiaryNode;
import com.stockcat.mapper.DiaryNodeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 我的日记（docs/API.md §7 / DATABASE.md §3.12）：飞书文档式树状长期笔记本。
 * - 树深度 ≤ 5（根为 1 层）
 * - 移动不得把自己或自己的子孙作为父节点（成环）
 * - FOLDER 删除为级联删除整棵子树，必须 confirm=true；返回将删除的文档数供前端强确认
 */
@Service
public class DiaryService {

    private static final int MAX_DEPTH = 5;

    private final DiaryNodeMapper mapper;

    public DiaryService(DiaryNodeMapper mapper) {
        this.mapper = mapper;
    }

    /** 树视图节点 */
    public record DiaryNodeView(Long id, Long parentId, String nodeType, String title,
                                Integer sortOrder, List<DiaryNodeView> children) {
    }

    /** 搜索命中：文档 + 树路径（自根至自身） */
    public record DiarySearchHit(Long id, String title, List<String> path) {
    }

    public List<DiaryNodeView> tree() {
        List<DiaryNode> all = mapper.selectAll();
        Map<Long, List<DiaryNode>> byParent = new HashMap<>();
        for (DiaryNode n : all) {
            byParent.computeIfAbsent(n.getParentId(), k -> new ArrayList<>()).add(n);
        }
        return buildChildren(null, byParent);
    }

    private List<DiaryNodeView> buildChildren(Long parentId, Map<Long, List<DiaryNode>> byParent) {
        List<DiaryNodeView> views = new ArrayList<>();
        for (DiaryNode n : byParent.getOrDefault(parentId, List.of())) {
            views.add(new DiaryNodeView(n.getId(), n.getParentId(), n.getNodeType(), n.getTitle(),
                    n.getSortOrder(), buildChildren(n.getId(), byParent)));
        }
        return views;
    }

    public DiaryNode get(Long id) {
        DiaryNode node = mapper.findById(id);
        if (node == null) {
            throw ApiException.notFound("日记节点不存在: id=" + id);
        }
        return node;
    }

    @Transactional
    public DiaryNode create(Long parentId, String nodeType, String title, String content,
                            String relatedInstrumentIds) {
        if (!"FOLDER".equals(nodeType) && !"DOC".equals(nodeType)) {
            throw ApiException.validationError("nodeType 仅支持 FOLDER/DOC");
        }
        if (title == null || title.isBlank()) {
            throw ApiException.validationError("title 必填");
        }
        if ("FOLDER".equals(nodeType) && content != null && !content.isBlank()) {
            throw ApiException.validationError("FOLDER 不能有正文内容");
        }
        int parentDepth = 0;
        if (parentId != null) {
            DiaryNode parent = get(parentId);
            if (!"FOLDER".equals(parent.getNodeType())) {
                throw ApiException.validationError("父节点必须是文件夹");
            }
            parentDepth = depthOf(parentId);
        }
        int depth = parentDepth + 1;
        if (depth > MAX_DEPTH) {
            throw ApiException.validationError("树深度不能超过 " + MAX_DEPTH + " 层");
        }

        DiaryNode node = new DiaryNode();
        node.setParentId(parentId);
        node.setNodeType(nodeType);
        node.setTitle(title.trim());
        node.setContent("DOC".equals(nodeType) ? content : null);
        node.setRelatedInstrumentIds(relatedInstrumentIds);
        node.setSortOrder(0);
        mapper.insert(node);
        return mapper.findById(node.getId());
    }

    /**
     * 更新：title/content/relatedInstrumentIds；移动 = parentId/sortOrder（成环与深度校验）。
     */
    @Transactional
    public DiaryNode update(Long id, String title, String content, String relatedInstrumentIds,
                            Long newParentId, Integer newSortOrder, boolean moving) {
        DiaryNode node = get(id);

        if (title != null) {
            if (title.isBlank()) {
                throw ApiException.validationError("title 不能为空");
            }
            node.setTitle(title.trim());
        }
        if (content != null) {
            if ("FOLDER".equals(node.getNodeType()) && !content.isBlank()) {
                throw ApiException.validationError("FOLDER 不能有正文内容");
            }
            node.setContent("DOC".equals(node.getNodeType()) ? content : null);
        }

        if (moving) {
            Long targetParent = newParentId != null ? newParentId : node.getParentId();
            if (targetParent != null) {
                if (targetParent.equals(id)) {
                    throw ApiException.validationError("不能把节点移动到自己之下");
                }
                DiaryNode targetParentNode = get(targetParent);
                if (!"FOLDER".equals(targetParentNode.getNodeType())) {
                    throw ApiException.validationError("目标父节点必须是文件夹");
                }
                // 成环检测：目标父节点不能是自己的子孙
                if (isDescendant(targetParent, id)) {
                    throw ApiException.validationError("不能把节点移动到自己的子孙之下（成环）");
                }
                // 深度校验：新父深度 + 1 + 子树高度 ≤ MAX_DEPTH
                int newDepth = depthOf(targetParent) + 1;
                int subtreeHeight = subtreeHeight(id);
                if (newDepth + subtreeHeight > MAX_DEPTH) {
                    throw ApiException.validationError("移动后子树深度将超过 " + MAX_DEPTH + " 层");
                }
            }
            mapper.updateFields(id, node.getTitle(), node.getContent(), node.getRelatedInstrumentIds(),
                    newSortOrder, newParentId, newParentId == null && node.getParentId() != null);
        } else {
            mapper.updateFields(id, node.getTitle(), node.getContent(), node.getRelatedInstrumentIds(),
                    null, null, false);
        }
        return mapper.findById(id);
    }

    /**
     * 删除：FOLDER 需 confirm=true 级联整棵子树；返回级联删除的文档数（供前端强确认）。
     */
    @Transactional
    public long delete(Long id, boolean confirm) {
        DiaryNode node = get(id);
        if ("FOLDER".equals(node.getNodeType())) {
            if (!confirm) {
                throw ApiException.validationError(
                        "删除文件夹将级联删除整棵子树，必须携带 confirm=true（将删除 "
                                + mapper.countDocsInSubtree(id) + " 篇文档）");
            }
            long docCount = mapper.countDocsInSubtree(id);
            mapper.deleteSubtree(id);
            return docCount;
        }
        mapper.deleteSubtree(id);
        // 删的是 1 篇文档，就报 1；报 0 会让"已删除"看起来像没删
        return 1;
    }

    public long countDocsInSubtree(Long id) {
        return mapper.countDocsInSubtree(id);
    }

    /**
     * 关键词搜索 DOC（title/content ILIKE），返回命中及树路径。
     */
    public List<DiarySearchHit> search(String keyword, int limit) {
        List<DiaryNode> hits = mapper.searchDocs(keyword, limit);
        Map<Long, DiaryNode> byId = new HashMap<>();
        for (DiaryNode n : mapper.selectAll()) {
            byId.put(n.getId(), n);
        }
        List<DiarySearchHit> result = new ArrayList<>();
        for (DiaryNode hit : hits) {
            List<String> path = new ArrayList<>();
            Long cursor = hit.getId();
            while (cursor != null) {
                DiaryNode n = byId.get(cursor);
                if (n == null) {
                    break;
                }
                path.add(0, n.getTitle());
                cursor = n.getParentId();
            }
            result.add(new DiarySearchHit(hit.getId(), hit.getTitle(), path));
        }
        return result;
    }

    // ---- 树工具 ----

    /** 节点深度（根=1） */
    private int depthOf(Long id) {
        int depth = 0;
        Long cursor = id;
        Set<Long> seen = new HashSet<>();
        while (cursor != null) {
            if (!seen.add(cursor)) {
                throw ApiException.validationError("检测到数据环，请检查树数据");
            }
            DiaryNode n = get(cursor);
            depth++;
            cursor = n.getParentId();
        }
        return depth;
    }

    /** 子树高度（叶子=0） */
    private int subtreeHeight(Long id) {
        int max = 0;
        for (DiaryNode child : childrenOf(id)) {
            max = Math.max(max, subtreeHeight(child.getId()) + 1);
        }
        return max;
    }

    private boolean isDescendant(Long candidate, Long ancestorId) {
        Long cursor = candidate;
        Set<Long> seen = new HashSet<>();
        while (cursor != null) {
            if (cursor.equals(ancestorId)) {
                return true;
            }
            if (!seen.add(cursor)) {
                return false;
            }
            cursor = get(cursor).getParentId();
        }
        return false;
    }

    private List<DiaryNode> childrenOf(Long parentId) {
        List<DiaryNode> children = new ArrayList<>();
        for (DiaryNode n : mapper.selectAll()) {
            if (parentId.equals(n.getParentId())) {
                children.add(n);
            }
        }
        return children;
    }
}
