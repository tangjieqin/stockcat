package com.stockcat.web;

import com.stockcat.common.ApiException;
import com.stockcat.service.LibraryService;
import com.stockcat.web.dto.LibraryDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 资料库（P3.1），对应 docs/API.md §7.2。
 *
 * <p>导入只支持两类来源：手工粘贴（{@code sourceType=PASTE}）与文本文件（{@code FILE}，md/txt）。
 * HTML 与 PDF 需要各自的正文提取，强行上会得到带导航栏与页脚的"资料"——检索质量从源头就坏了。
 * 上传这两类文件时明确拒绝并说明该怎么做，而不是静默失败。
 */
@RestController
@RequestMapping("/api/v1/library")
public class LibraryController {

    private static final Set<String> TEXT_EXTENSIONS = Set.of("md", "markdown", "txt");

    private final LibraryService service;

    public LibraryController(LibraryService service) {
        this.service = service;
    }

    @GetMapping("/documents")
    public PageResult list(@RequestParam(required = false) String keyword,
                           @RequestParam(required = false) Integer page,
                           @RequestParam(required = false) Integer size) {
        return service.list(keyword, page, size);
    }

    @GetMapping("/documents/{id}")
    public DocumentDetail detail(@PathVariable Long id) {
        return service.detail(id);
    }

    /**
     * 片段级检索（P3.2）。关键词为空返回 400——空查询不能变成"列出全部片段"。
     *
     * @param instrumentId 只看关联了某标的的资料，可选
     */
    @GetMapping("/search")
    public SearchResult search(@RequestParam(required = false) String keyword,
                               @RequestParam(required = false) Integer limit,
                               @RequestParam(required = false) Long instrumentId) {
        return service.search(keyword, limit, instrumentId);
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentDetail create(@Valid @RequestBody CreateDocumentRequest req) {
        return service.create(req);
    }

    /**
     * 上传文本文件导入。{@code title} 缺省用文件名（去扩展名）。
     */
    @PostMapping("/documents/import")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentDetail importFile(@RequestParam("file") MultipartFile file,
                                     @RequestParam(required = false) String title,
                                     @RequestParam(required = false) String publisher) {
        if (file == null || file.isEmpty()) {
            throw ApiException.validationError("文件为空");
        }
        String filename = file.getOriginalFilename() == null ? "未命名" : file.getOriginalFilename();
        String extension = extensionOf(filename);

        if ("pdf".equals(extension)) {
            throw ApiException.validationError(
                    "暂不支持 PDF：PDF 没有段落概念，解析结果常带页眉页脚与双栏错序。"
                            + "请复制正文后用「粘贴」导入");
        }
        if (!TEXT_EXTENSIONS.contains(extension)) {
            throw ApiException.validationError(
                    "只支持 .md / .markdown / .txt 文本文件；网页内容请复制正文后粘贴导入");
        }

        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw ApiException.validationError("读取文件失败：" + e.getMessage());
        }

        String effectiveTitle = title == null || title.isBlank()
                ? stripExtension(filename) : title;
        return service.create(new CreateDocumentRequest(
                effectiveTitle, "FILE", filename, publisher, null, content, null, null));
    }

    @DeleteMapping("/documents/{id}")
    public Map<String, Object> delete(@PathVariable Long id,
                                      @RequestParam(defaultValue = "false") boolean confirm) {
        int deletedChunks = service.delete(id, confirm);
        return Map.of("deletedChunkCount", deletedChunks);
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        String name = dot < 0 ? filename : filename.substring(0, dot);
        return name.isBlank() ? "未命名" : name;
    }
}
