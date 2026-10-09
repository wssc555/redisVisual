package com.example.redisadmin.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 版本感知的 Schema 迁移。幂等只靠版本号（不依赖 CREATE INDEX IF NOT EXISTS 之类方言差异）。
 * <p>启动即执行：应用在无 Redis 可达时也照常启动。</p>
 */
@Component
@Order(1)
public class SchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    /**
     * 当前代码期望的 schema 版本。
     */
    private static final int TARGET_VERSION = 2;

    private final JdbcTemplate jdbcTemplate;
    private final StorageDialect dialect;
    private final LegacyConnectionImporter importer;

    public SchemaInitializer(JdbcTemplate jdbcTemplate, StorageDialect dialect, LegacyConnectionImporter importer) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
        this.importer = importer;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute(dialect.createSchemaVersionTableSql());

        int current = dialect.queryCurrentVersion();
        if (current >= TARGET_VERSION) {
            log.info("Schema 已是最新版本 v{}（方言 {}），跳过迁移", current, dialect.type());
        } else {
            for (int version = current + 1; version <= TARGET_VERSION; version++) {
                migrateTo(version);
            }
        }

        importer.importIfRequested(args);
    }

    private void migrateTo(int version) {
        switch (version) {
            case 1 -> {
                jdbcTemplate.execute(dialect.createRedisProfileTableSql());
                log.info("Schema 迁移完成: v1 建立 redis_profile 表（方言 {}）", dialect.type());
            }
            case 2 -> {
                for (String sql : dialect.createKeyHistorySqls()) {
                    jdbcTemplate.execute(sql);
                }
                log.info("Schema 迁移完成: v2 建立 key_history 表及索引（方言 {}）", dialect.type());
            }
            default -> throw new IllegalStateException("未定义的 Schema 版本: " + version);
        }
        dialect.insertVersion(version);
    }
}
