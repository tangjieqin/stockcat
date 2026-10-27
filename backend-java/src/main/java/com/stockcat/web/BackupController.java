package com.stockcat.web;

import com.stockcat.service.BackupService;
import com.stockcat.service.BackupService.BackupFile;
import com.stockcat.service.BackupService.BackupOverview;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 数据库备份，对应 docs/API.md §12。
 *
 * <p>只做导出与文件管理。**恢复不在页面上做**：{@code pg_restore} 灌回已有库会撞主键，
 * 或需要先清空整个 schema，一次误点就没了全部流水。恢复要走命令行，见 DATABASE.md §7。
 */
@RestController
@RequestMapping("/api/v1/backup")
public class BackupController {

    private final BackupService service;

    public BackupController(BackupService service) {
        this.service = service;
    }

    @GetMapping
    public BackupOverview overview() {
        return service.overview();
    }

    @PostMapping("/export")
    public BackupFile export() {
        return service.export();
    }

    @GetMapping("/{name}/download")
    public ResponseEntity<Resource> download(@PathVariable String name) {
        BackupFile file = service.require(name);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.name() + "\"")
                .contentLength(file.sizeBytes())
                .body(new FileSystemResource(file.path()));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        service.delete(name);
        return ResponseEntity.noContent().build();
    }
}
