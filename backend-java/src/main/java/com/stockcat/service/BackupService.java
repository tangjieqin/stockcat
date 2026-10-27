package com.stockcat.service;

import com.stockcat.common.ApiException;
import com.stockcat.common.ErrorCode;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 数据库备份（P4 前置小件）：调 {@code pg_dump -Fc} 导出，扫目录列历史。
 *
 * <p><b>为什么调外部进程而不是纯 Java 导 SQL</b>：{@code -Fc} 是 PostgreSQL 官方的自定义格式，
 * 恢复时 {@code pg_restore --clean --if-exists} 一条命令就够，不依赖本系统还在不在。
 * 自己拼 {@code INSERT} 语句要处理转义、identity 序列、外键顺序、{@code bytea}，
 * 这些地方每一个出错都只在"真的需要恢复"那一刻才暴露——那是备份最不能失效的时刻。
 * 代价是要求环境里有 {@code pg_dump}，所以见下一条。
 *
 * <p><b>为什么不假设有本地 pg_dump</b>：实测宿主机没有 {@code pg_dump}，但 PostgreSQL 跑在
 * docker 里（{@code docker compose up -d} 起的 {@code stockcat-postgres}），容器内自带
 * {@code pg_dump} 16.15。所以默认走 {@code docker exec}，容器内用 unix socket 连接，
 * 连口令都不需要传。装了本机 PostgreSQL 客户端的，把 {@code pg-dump} 配成完整路径即可切换。
 *
 * <p><b>为什么备份历史不建表</b>：备份正是数据库出问题时的依靠。把"有哪些备份"记在数据库里，
 * 等于把钥匙锁在要救的那间屋子里。直接扫目录，库挂了列表照样能看。
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    /** 只认自己生成的文件名，同时用作路径穿越的防线（{@link #resolve} 里双重校验）。 */
    static final Pattern FILE_NAME = Pattern.compile("^stockcat-\\d{8}-\\d{6}(-\\d+)?\\.dump$");
    static final String FILE_SUFFIX = ".dump";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 导出超时。个人库正常 1 秒内完成；给 5 分钟是为了容忍"行情快照表长到几十万行"的情况，
     * 而不是让页面上转圈到用户自己刷新。
     */
    static final Duration TIMEOUT = Duration.ofMinutes(5);

    private final Path dir;
    private final String container;
    private final String pgDumpPath;
    private final DbTarget db;
    private final String password;
    private final CommandRunner runner;

    /**
     * 执行外部命令并把 stdout 写进 {@code outFile}、stderr 写进 {@code errFile}。
     *
     * <p>抽成接口是为了能单测失败分支：「pg_dump 退出码 2 时不能留下半截文件」这种事，
     * 不可能靠真跑 docker 来验证（容器状态、权限、平台各不相同）。
     */
    @FunctionalInterface
    interface CommandRunner {
        void run(List<String> cmd, Path outFile, Path errFile);
    }

    @Autowired
    public BackupService(@Value("${stockcat.backup.dir:../backups}") String dir,
                         @Value("${stockcat.backup.docker-container:stockcat-postgres}") String container,
                         @Value("${stockcat.backup.pg-dump:}") String pgDumpPath,
                         @Value("${spring.datasource.url}") String jdbcUrl,
                         @Value("${spring.datasource.username}") String username,
                         @Value("${spring.datasource.password:}") String password) {
        this(dir, container, pgDumpPath, jdbcUrl, username, password, null);
    }

    /** 测试用：最后一个参数可替换真实进程执行。 */
    BackupService(String dir, String container, String pgDumpPath, String jdbcUrl,
                  String username, String password, CommandRunner runner) {
        // 相对路径相对"启动进程的工作目录"，IDE 里跑和命令行跑可能不同，所以一律存绝对路径，
        // 接口里也把绝对路径返回给页面——用户得知道文件到底落在哪。
        this.dir = Paths.get(dir).toAbsolutePath().normalize();
        this.container = container;
        this.pgDumpPath = pgDumpPath == null ? "" : pgDumpPath.trim();
        this.db = parseJdbcUrl(jdbcUrl, username);
        this.password = password == null ? "" : password;
        this.runner = runner != null ? runner : this::runProcess;
    }

    /**
     * 一条备份记录。{@code createdAt} 取文件修改时间（导出完成时刻），不是文件名里的发起时刻。
     *
     * <p>{@code path} 不序列化：页面拿文件名就够下载了，把服务器本地路径返给前端毫无用处，
     * 而 Jackson 会把它写成 {@code file:///D:/...} 这种既不是路径也不像 URL 的字符串。
     */
    public record BackupFile(String name, long sizeBytes, Instant createdAt, @JsonIgnore Path path) {
    }

    /**
     * 页面顶部要显示的"当前怎么导出的"，外加一条恢复命令。
     *
     * <p>把恢复命令也由后端拼，是因为它和导出走同一个环境：容器名、库名、用户都取自同一份配置。
     * 让前端各写一份，改了配置就会给出跑不通的命令——那比不给更糟。
     */
    public record BackupOverview(String dir, String method, String restoreCommand,
                                 boolean usesDocker, List<BackupFile> files) {
    }

    record DbTarget(String host, int port, String database, String user) {
    }

    public BackupOverview overview() {
        List<String> cmd = buildCommand(pgDumpPath, container, db);
        return new BackupOverview(dir.toString(), String.join(" ", cmd),
                restoreCommand(), pgDumpPath.isBlank(), list());
    }

    /** 恢复命令模板：文件路径留成占位符，由用户替换成实际的那一份。 */
    private String restoreCommand() {
        if (!pgDumpPath.isBlank()) {
            return "pg_restore --clean --if-exists -h " + db.host() + " -p " + db.port()
                    + " -U " + db.user() + " -d " + db.database() + " <备份文件完整路径>";
        }
        return "docker exec -i " + container + " pg_restore --clean --if-exists -U "
                + db.user() + " -d " + db.database() + " < <备份文件完整路径>";
    }

    /** 按修改时间倒序——最近一次备份排最前，那是最可能要用到的。 */
    public List<BackupFile> list() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<BackupFile> files = new ArrayList<>();
            stream.filter(p -> FILE_NAME.matcher(p.getFileName().toString()).matches())
                    .forEach(p -> files.add(describe(p)));
            files.sort(Comparator.comparing(BackupFile::createdAt).reversed());
            return files;
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "读取备份目录失败：" + dir + "（" + e.getMessage() + "）");
        }
    }

    /**
     * 生成一份备份。
     *
     * <p>先写到 {@code .part} 临时文件，成功后才改名成正式文件名——中途失败或进程被杀时，
     * 目录里不会留下一个"看起来像备份、实际是半截"的 dump。半截文件比没有文件更危险：
     * 真到要恢复那天才发现打不开。
     */
    public BackupFile export() {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw unavailable("无法创建备份目录 " + dir + "（" + e.getMessage() + "）");
        }

        Path tmp = null;
        Path errFile = null;
        try {
            tmp = Files.createTempFile(dir, ".export-", ".part");
            errFile = Files.createTempFile(dir, ".stderr-", ".log");
            List<String> cmd = buildCommand(pgDumpPath, container, db);
            log.info("开始备份：{}", String.join(" ", cmd));
            runner.run(cmd, tmp, errFile);

            Path target = dir.resolve(nextName());
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            tmp = null;
            BackupFile file = describe(target);
            log.info("备份完成：{}（{} 字节）", file.name(), file.sizeBytes());
            return file;
        } catch (IOException e) {
            throw unavailable("备份失败：" + e.getMessage());
        } finally {
            deleteQuietly(tmp);
            deleteQuietly(errFile);
        }
    }

    /** 下载前解析文件名。非法名字一律 404——不告诉调用方"你这个名字格式不对"，避免成为探测工具。 */
    public BackupFile require(String name) {
        if (name == null || !FILE_NAME.matcher(name).matches()) {
            throw ApiException.notFound("备份文件不存在：" + name);
        }
        Path path = dir.resolve(name).normalize();
        // 正则已经挡住了 ../，这里再校验一次父目录：两道防线任意一道失效都不至于读到目录外
        if (!path.startsWith(dir) || !Files.isRegularFile(path)) {
            throw ApiException.notFound("备份文件不存在：" + name);
        }
        return describe(path);
    }

    public void delete(String name) {
        BackupFile file = require(name);
        try {
            Files.delete(file.path());
            log.info("已删除备份：{}", name);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "删除备份失败：" + e.getMessage());
        }
    }

    /**
     * 拼 pg_dump 命令。抽成静态方法是为了能单测——真正执行进程的部分在容器里测不了。
     *
     * <p>docker 方式不带 {@code -h/-p}：容器内的 pg_dump 走 unix socket 连本机 PostgreSQL，
     * 而 socket 连接的 pg_hba 规则是 trust，所以既不用传口令，也不会把口令写进进程参数
     * （{@code ps} 里看得见）。
     */
    static List<String> buildCommand(String pgDumpPath, String container, DbTarget db) {
        if (pgDumpPath != null && !pgDumpPath.isBlank()) {
            return List.of(pgDumpPath.trim(), "-Fc",
                    "-h", db.host(), "-p", String.valueOf(db.port()),
                    "-U", db.user(), "-d", db.database());
        }
        return List.of("docker", "exec", container, "pg_dump", "-Fc",
                "-U", db.user(), "-d", db.database());
    }

    static DbTarget parseJdbcUrl(String jdbcUrl, String fallbackUser) {
        // jdbc:postgresql://localhost:5432/stockcat[?params]
        String rest = jdbcUrl.substring(jdbcUrl.indexOf("//") + 2);
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        String database = slash < 0 ? "" : rest.substring(slash + 1);
        int query = database.indexOf('?');
        if (query >= 0) {
            database = database.substring(0, query);
        }
        String host = authority;
        int port = 5432;
        int colon = authority.lastIndexOf(':');
        if (colon >= 0) {
            host = authority.substring(0, colon);
            port = Integer.parseInt(authority.substring(colon + 1));
        }
        return new DbTarget(host.isBlank() ? "localhost" : host, port, database, fallbackUser);
    }

    /** 真实执行：redirect 到文件而不是读管道，避免 stdout 写满缓冲区时进程卡死。 */
    private void runProcess(List<String> cmd, Path outFile, Path errFile) {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectOutput(outFile.toFile())
                .redirectError(errFile.toFile());
        if (!pgDumpPath.isBlank() && !password.isEmpty()) {
            pb.environment().put("PGPASSWORD", password);
        }

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            // 最常见的两种：docker 没装/没在 PATH，或 pg-dump 配了个不存在的路径。
            // 这里必须给出可执行的下一步，否则用户只看到"Cannot run program"完全无从下手。
            throw unavailable(explainStartFailure(cmd.get(0), e));
        }

        try {
            if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw unavailable("备份超时（超过 " + TIMEOUT.toMinutes() + " 分钟），已终止 pg_dump");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw unavailable("备份被中断");
        }

        int code = process.exitValue();
        if (code != 0) {
            throw unavailable(explainExit(cmd, code, readTail(errFile)));
        }
        if (!Files.exists(outFile) || sizeOf(outFile) == 0) {
            throw unavailable("pg_dump 返回成功但没有产出内容，请检查数据库连接配置");
        }
    }

    private String explainStartFailure(String program, IOException e) {
        if (program.contains("docker")) {
            return "找不到 docker 命令。两种解决方式：① 启动 Docker Desktop（容器 stockcat-postgres "
                    + "需要是运行状态）后重试；② 安装 PostgreSQL 客户端，把 pg_dump 完整路径填进配置项 "
                    + "stockcat.backup.pg-dump。原始错误：" + e.getMessage();
        }
        return "找不到可执行的 " + program + "。请检查配置项 stockcat.backup.pg-dump 是否指向真实存在的 "
                + "pg_dump。原始错误：" + e.getMessage();
    }

    private String explainExit(List<String> cmd, int code, String stderr) {
        String lower = stderr.toLowerCase();
        if (lower.contains("no such container")) {
            return "容器 " + container + " 不存在或未运行。先执行 docker compose up -d。pg_dump 输出："
                    + stderr;
        }
        if (lower.contains("cannot connect to the docker daemon")) {
            return "连不上 Docker 守护进程，请确认 Docker Desktop 已启动。pg_dump 输出：" + stderr;
        }
        if (lower.contains("password authentication failed")) {
            return "数据库认证失败。走本机 pg_dump 时口令取自 spring.datasource.password，"
                    + "请检查 .env 里的 STOCKCAT_DB_PASSWORD。pg_dump 输出：" + stderr;
        }
        return "pg_dump 退出码 " + code + "（命令：" + String.join(" ", cmd) + "）。输出：" + stderr;
    }

    /** 只留最后 800 字符：pg_dump 的报错常以多行调用栈收尾，前面才是关键。 */
    private String readTail(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8).strip();
            return text.length() <= 800 ? text : "…" + text.substring(text.length() - 800);
        } catch (IOException e) {
            return "(无法读取错误输出)";
        }
    }

    private BackupFile describe(Path path) {
        long size = sizeOf(path);
        Instant createdAt;
        try {
            createdAt = Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            createdAt = Instant.EPOCH;
        }
        return new BackupFile(path.getFileName().toString(), size, createdAt, path);
    }

    private long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    /** 同一秒内点两次「立即备份」时，第二个加 -2 后缀，不覆盖第一份。 */
    private String nextName() {
        String base = "stockcat-" + LocalDateTime.now(ZoneId.systemDefault()).format(STAMP);
        String name = base + FILE_SUFFIX;
        int seq = 2;
        while (Files.exists(dir.resolve(name))) {
            name = base + "-" + seq++ + FILE_SUFFIX;
        }
        return name;
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("临时文件清理失败：{}", path, e);
        }
    }

    private ApiException unavailable(String message) {
        return new ApiException(ErrorCode.BACKUP_UNAVAILABLE, message);
    }
}
