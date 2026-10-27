package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.common.ErrorCode;
import com.stockcat.service.BackupService.BackupFile;
import com.stockcat.service.BackupService.BackupOverview;
import com.stockcat.service.BackupService.DbTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupServiceTest {

    private static final String JDBC = "jdbc:postgresql://localhost:5432/stockcat";

    private BackupService service(Path dir, BackupService.CommandRunner runner) {
        return new BackupService(dir.toString(), "stockcat-postgres", "", JDBC,
                "stockcat", "secret", runner);
    }

    private BackupService service(Path dir, String pgDumpPath) {
        return new BackupService(dir.toString(), "stockcat-postgres", pgDumpPath, JDBC,
                "stockcat", "secret", (cmd, out, err) -> {
                    throw new AssertionError("这个测试不该执行进程");
                });
    }

    // ---------- 命令拼装 ----------

    @Test
    void defaultsToDockerExecBecauseHostHasNoPgDump() {
        // 实测宿主机没有 pg_dump，但 PostgreSQL 跑在容器里；容器内 socket 连接是 trust，
        // 所以命令里既没有 -h/-p，也不该出现口令
        List<String> cmd = BackupService.buildCommand("", "stockcat-postgres",
                new DbTarget("localhost", 5432, "stockcat", "stockcat"));

        assertThat(cmd).containsExactly("docker", "exec", "stockcat-postgres", "pg_dump",
                "-Fc", "-U", "stockcat", "-d", "stockcat");
    }

    @Test
    void usesLocalPgDumpWhenConfigured() {
        List<String> cmd = BackupService.buildCommand("C:/PostgreSQL/16/bin/pg_dump.exe",
                "stockcat-postgres", new DbTarget("db.local", 6543, "stockcat", "stockcat"));

        assertThat(cmd).containsExactly("C:/PostgreSQL/16/bin/pg_dump.exe", "-Fc",
                "-h", "db.local", "-p", "6543", "-U", "stockcat", "-d", "stockcat");
    }

    @Test
    void parsesJdbcUrlWithPort() {
        assertThat(BackupService.parseJdbcUrl(JDBC, "stockcat"))
                .isEqualTo(new DbTarget("localhost", 5432, "stockcat", "stockcat"));
    }

    @Test
    void parsesJdbcUrlWithoutPortAndStripsQuery() {
        assertThat(BackupService.parseJdbcUrl(
                "jdbc:postgresql://127.0.0.1/stockcat?sslmode=disable", "u"))
                .isEqualTo(new DbTarget("127.0.0.1", 5432, "stockcat", "u"));
    }

    // ---------- 列表与文件名 ----------

    @Test
    void listIgnoresForeignFilesAndNewestComesFirst() throws IOException {
        Path dir = Files.createTempDirectory("backup-list");
        try {
            writeDump(dir, "stockcat-20260101-030000.dump", Instant.parse("2026-01-01T03:00:00Z"));
            writeDump(dir, "stockcat-20261027-214057.dump", Instant.parse("2026-10-27T13:40:57Z"));
            // 目录里可能有备份时留下的临时文件，或用户自己放的东西——都不该出现在列表里
            Files.writeString(dir.resolve(".export-123.part"), "半截");
            Files.writeString(dir.resolve("我的笔记.txt"), "不相关");

            List<BackupFile> files = service(dir, "").list();

            assertThat(files).extracting(BackupFile::name)
                    .containsExactly("stockcat-20261027-214057.dump", "stockcat-20260101-030000.dump");
            assertThat(files.get(0).sizeBytes()).isEqualTo("dump-bytes".length());
        } finally {
            deleteDir(dir);
        }
    }

    @Test
    void listReturnsEmptyWhenDirectoryDoesNotExist() {
        assertThat(service(Path.of("build", "no-such-backup-dir"), "").list()).isEmpty();
    }

    @Test
    void rejectNamesThatEscapeTheDirectory() throws IOException {
        Path dir = Files.createTempDirectory("backup-guard");
        try {
            BackupService svc = service(dir, "");
            // 路径穿越必须 404，而不是"格式不对"——后者等于告诉别人怎么试
            assertThatThrownBy(() -> svc.require("../.env"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getErrorCode())
                            .isEqualTo(ErrorCode.NOT_FOUND));
            assertThatThrownBy(() -> svc.require("stockcat-20260101-030000.dump.bak"))
                    .isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> svc.require(null)).isInstanceOf(ApiException.class);
        } finally {
            deleteDir(dir);
        }
    }

    @Test
    void requireFindsExistingBackup() throws IOException {
        Path dir = Files.createTempDirectory("backup-require");
        try {
            writeDump(dir, "stockcat-20260101-030000.dump", Instant.parse("2026-01-01T03:00:00Z"));

            assertThat(service(dir, "").require("stockcat-20260101-030000.dump").sizeBytes())
                    .isEqualTo("dump-bytes".length());
        } finally {
            deleteDir(dir);
        }
    }

    // ---------- 导出 ----------

    @Test
    void exportWritesFileAndLeavesNoTempArtifacts() throws IOException {
        Path dir = Files.createTempDirectory("backup-export");
        try {
            BackupService svc = service(dir, (cmd, out, err) -> {
                try {
                    Files.writeString(out, "dump-bytes");
                    Files.writeString(err, "");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });

            BackupFile file = svc.export();

            assertThat(file.name()).matches("^stockcat-\\d{8}-\\d{6}\\.dump$");
            assertThat(file.sizeBytes()).isEqualTo("dump-bytes".length());
            assertThat(Files.readString(file.path())).isEqualTo("dump-bytes");
            assertThat(tempArtifacts(dir)).isEmpty();
        } finally {
            deleteDir(dir);
        }
    }

    @Test
    void exportFailureLeavesNoHalfWrittenFile() throws IOException {
        Path dir = Files.createTempDirectory("backup-fail");
        try {
            BackupService svc = service(dir, (cmd, out, err) -> {
                try {
                    // 模拟 pg_dump 写了一半才失败：临时文件里有内容，但这份备份是废的
                    Files.writeString(out, "half-a-dump");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                throw new ApiException(ErrorCode.BACKUP_UNAVAILABLE, "pg_dump 退出码 1");
            });

            assertThatThrownBy(svc::export)
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("退出码");

            try (Stream<Path> stream = Files.list(dir)) {
                assertThat(stream.map(p -> p.getFileName().toString()).toList())
                        .as("半截的 dump 比没有更危险——真到要恢复那天才发现打不开")
                        .isEmpty();
            }
        } finally {
            deleteDir(dir);
        }
    }

    @Test
    void secondExportInSameSecondDoesNotOverwriteFirst() throws IOException {
        Path dir = Files.createTempDirectory("backup-twice");
        try {
            BackupService svc = service(dir, (cmd, out, err) -> {
                try {
                    Files.writeString(out, "dump-bytes");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });

            BackupFile first = svc.export();
            BackupFile second = svc.export();

            assertThat(second.name()).isNotEqualTo(first.name());
            assertThat(svc.list()).hasSize(2);
        } finally {
            deleteDir(dir);
        }
    }

    // ---------- 概览 ----------

    @Test
    void overviewReportsHowItWillRunAndTheAbsoluteDirectory() throws IOException {
        Path dir = Files.createTempDirectory("backup-overview");
        try {
            BackupOverview overview = service(dir, "").overview();

            assertThat(overview.usesDocker()).isTrue();
            assertThat(overview.method()).isEqualTo(
                    "docker exec stockcat-postgres pg_dump -Fc -U stockcat -d stockcat");
            // 相对路径在 IDE 与命令行下指向不同地方，所以对外一律给绝对路径
            assertThat(Path.of(overview.dir()).isAbsolute()).isTrue();
        } finally {
            deleteDir(dir);
        }
    }

    @Test
    void deleteRemovesFileButKeepsOthers() throws IOException {
        Path dir = Files.createTempDirectory("backup-delete");
        try {
            writeDump(dir, "stockcat-20260101-030000.dump", Instant.parse("2026-01-01T03:00:00Z"));
            writeDump(dir, "stockcat-20260102-030000.dump", Instant.parse("2026-01-02T03:00:00Z"));
            BackupService svc = service(dir, "");

            svc.delete("stockcat-20260101-030000.dump");

            assertThat(svc.list()).extracting(BackupFile::name)
                    .containsExactly("stockcat-20260102-030000.dump");
        } finally {
            deleteDir(dir);
        }
    }

    private void writeDump(Path dir, String name, Instant modified) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, "dump-bytes", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.from(modified));
    }

    private List<String> tempArtifacts(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("."))
                    .toList();
        }
    }

    private void deleteDir(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                Files.deleteIfExists(p);
            }
        }
        Files.deleteIfExists(dir);
    }
}
