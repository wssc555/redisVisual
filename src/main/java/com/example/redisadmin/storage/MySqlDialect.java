package com.example.redisadmin.storage;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * MySQL 8 方言。标识符用反引号，保留字（如 {@code key}）需 quoteIdent 处理。
 */
public class MySqlDialect implements StorageDialect {

    private final JdbcTemplate jdbcTemplate;

    public MySqlDialect(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public JdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }

    @Override
    public StorageType type() {
        return StorageType.MYSQL;
    }

    @Override
    public String quoteIdent(String ident) {
        return "`" + ident + "`";
    }

    @Override
    public String createSchemaVersionTableSql() {
        return "CREATE TABLE IF NOT EXISTS schema_version ("
                + "version INT NOT NULL,"
                + "applied_at TIMESTAMP NOT NULL"
                + ")";
    }

    @Override
    public String createRedisProfileTableSql() {
        return "CREATE TABLE IF NOT EXISTS redis_profile ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,"
                + "name VARCHAR(128) NOT NULL,"
                + "mode VARCHAR(16) NOT NULL,"
                + "host VARCHAR(255) NULL,"
                + "port INT NULL,"
                + "nodes TEXT NULL,"
                + "sentinels TEXT NULL,"
                + "master_name VARCHAR(128) NULL,"
                + "database INT NOT NULL DEFAULT 0,"
                + "username VARCHAR(128) NULL,"
                + "password VARCHAR(512) NULL,"
                + "sentinel_password VARCHAR(512) NULL,"
                + "created_at TIMESTAMP NOT NULL,"
                + "updated_at TIMESTAMP NOT NULL,"
                + "PRIMARY KEY (id),"
                + "CONSTRAINT uq_redis_profile_name UNIQUE (name)"
                + ")";
    }

    @Override
    public List<String> createKeyHistorySqls() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS key_history ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT,"
                        + "profile_id BIGINT NOT NULL,"
                        + "db INT NOT NULL,"
                        + "key_name VARCHAR(512) NOT NULL,"
                        + "key_type VARCHAR(16) NULL,"
                        + "operation VARCHAR(16) NOT NULL,"
                        + "value_preview VARCHAR(1024) NULL,"
                        + "value_bytes BIGINT NULL,"
                        + "operator VARCHAR(64) NOT NULL,"
                        + "event_time DATETIME NOT NULL,"
                        + "PRIMARY KEY (id),"
                        + "KEY idx_key_history_profile_time (profile_id, event_time),"
                        + "KEY idx_key_history_key (key_name(191))"
                        + ")"
        );
    }

}
