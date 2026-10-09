package com.example.redisadmin.storage;

import com.example.redisadmin.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 数据目录解析：{@code app.db-path} 双看——
 * <ol>
 *   <li>{@link ApplicationArguments} 非选项参数优先（{@code java -jar app.jar /data/redis/app.db}）</li>
 *   <li>{@link Environment} 兜底（{@code --app.db-path=...} 或 {@code APP_DBPATH} 环境变量）</li>
 *   <li>缺省 {@code <工作目录>/data/app.db}</li>
 * </ol>
 * 密钥文件与数据库文件同目录（{@code app.key}），二者必须一起备份。
 */
@Component
public class AppPaths {

    private static final Logger log = LoggerFactory.getLogger(AppPaths.class);

    private final Path databasePath;
    private final Path keyFilePath;

    public AppPaths(ApplicationArguments args, Environment env, AppProperties properties) {
        String configured = firstNonBlank(
                firstNonOptionArgument(args),
                env.getProperty("app.db-path"),
                properties.getStorage().getDbPath());

        Path path = configured != null
                ? Paths.get(configured).toAbsolutePath().normalize()
                : Paths.get("data").toAbsolutePath().normalize().resolve("app.db");

        // 容错:调用方误传目录(如 OS app-data 目录)时,按数据目录对待,取其下 app.db。
        // 契约上 db-path 是数据库文件路径;目录容错仅为兜底,打 WARN 提示调用方纠正。
        if (Files.isDirectory(path)) {
            log.warn("app.db-path 指向的是目录({}),按数据目录处理,使用其下 app.db", path);
            path = path.resolve("app.db");
        }

        this.databasePath = path;
        this.keyFilePath = path.resolveSibling("app.key");
    }

    private static String firstNonOptionArgument(ApplicationArguments args) {
        if (args == null) {
            return null;
        }
        return args.getNonOptionArgs().stream()
                .findFirst()
                .map(String::valueOf)
                .filter(s -> !s.isBlank())
                .orElse(null);
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    public Path getDatabasePath() {
        return databasePath;
    }

    public Path getKeyFilePath() {
        return keyFilePath;
    }

    /**
     * 打印一次实际生效的路径（不含任何凭据）。
     */
    public void logResolvedPaths() {
        log.info("数据目录已解析: db={}, key={}", databasePath, keyFilePath);
    }
}
