package com.example.redisadmin.storage;

import com.example.redisadmin.config.AppProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 数据源装配。按 {@code redis-admin.storage.type} 选择方言。
 * <p>SQLite 的 PRAGMA 走 {@link SQLiteConfig.Pragma} 枚举设置到 JDBC 连接属性，
 * 而非执行硬编码 PRAGMA 字符串（xerial 驱动下不可靠）。</p>
 */
@Configuration
public class StorageDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageDataSourceConfig.class);

    private final AppProperties properties;
    private final AppPaths appPaths;

    public StorageDataSourceConfig(AppProperties properties, AppPaths appPaths) {
        this.properties = properties;
        this.appPaths = appPaths;
    }

    @Bean
    @Primary
    public DataSource dataSource() {
        StorageType type = StorageType.from(properties.getStorage().getType());
        return switch (type) {
            case SQLITE -> sqliteDataSource();
            case MYSQL, POSTGRES -> externalDataSource(type);
        };
    }

    private DataSource sqliteDataSource() {
        Path path = appPaths.getDatabasePath();
        try {
            Files.createDirectories(path.getParent());
        } catch (Exception e) {
            throw new IllegalStateException("无法创建数据目录: " + path.getParent(), e);
        }

        SQLiteConfig config = new SQLiteConfig();
        config.setPragma(SQLiteConfig.Pragma.JOURNAL_MODE, "WAL");
        config.setPragma(SQLiteConfig.Pragma.SYNCHRONOUS, "NORMAL");
        config.setPragma(SQLiteConfig.Pragma.FOREIGN_KEYS, "true");

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl("jdbc:sqlite:" + path);
        hikari.setDataSourceProperties(config.toProperties());
        hikari.setMaximumPoolSize(1);
        hikari.setPoolName("sqlite-pool");
        log.info("使用 SQLite 存储: {}", path);
        return new HikariDataSource(hikari);
    }

    private DataSource externalDataSource(StorageType type) {
        AppProperties.Storage storage = properties.getStorage();
        if (storage.getUrl() == null || storage.getUrl().isBlank()) {
            throw new IllegalStateException("存储方言为 " + type + " 时必须配置 redis-admin.storage.url");
        }
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(storage.getUrl());
        hikari.setUsername(storage.getUsername());
        hikari.setPassword(storage.getPassword());
        hikari.setMaximumPoolSize(10);
        hikari.setPoolName(type.name().toLowerCase() + "-pool");
        log.info("使用 {} 存储（连接串来自配置，不回显）", type);
        return new HikariDataSource(hikari);
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @Primary
    public StorageDialect storageDialect(JdbcTemplate jdbcTemplate) {
        StorageType type = StorageType.from(properties.getStorage().getType());
        return switch (type) {
            case SQLITE -> new SqliteDialect(jdbcTemplate);
            case MYSQL -> new MySqlDialect(jdbcTemplate);
            case POSTGRES -> new PostgresDialect(jdbcTemplate);
        };
    }
}
