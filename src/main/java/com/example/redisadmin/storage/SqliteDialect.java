package com.example.redisadmin.storage;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * SQLite 方言。默认形态，零配置随应用走。
 * <p>注意：SQLite 的 PRAGMA 走数据源上的 {@code SQLiteConfig.Pragma} 枚举设置
 * （见 {@link StorageDataSourceConfig}），不在此处硬编码 PRAGMA 字符串
 * —— 硬编码在 xerial 驱动上不可靠。</p>
 */
public class SqliteDialect implements StorageDialect {

    private final JdbcTemplate jdbcTemplate;

    public SqliteDialect(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public JdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }

    @Override
    public StorageType type() {
        return StorageType.SQLITE;
    }

    @Override
    public String quoteIdent(String ident) {
        return "\"" + ident + "\"";
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
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name VARCHAR(128) NOT NULL,"
                + "mode VARCHAR(16) NOT NULL,"
                + "host VARCHAR(255),"
                + "port INT,"
                + "nodes TEXT,"
                + "sentinels TEXT,"
                + "master_name VARCHAR(128),"
                + "database INT NOT NULL DEFAULT 0,"
                + "username VARCHAR(128),"
                + "password VARCHAR(512),"
                + "sentinel_password VARCHAR(512),"
                + "created_at TIMESTAMP NOT NULL,"
                + "updated_at TIMESTAMP NOT NULL,"
                + "CONSTRAINT uq_redis_profile_name UNIQUE (name)"
                + ")";
    }

    @Override
    public List<String> createKeyHistorySqls() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS key_history ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "profile_id INTEGER NOT NULL,"
                        + "db INT NOT NULL,"
                        + "key_name VARCHAR(512) NOT NULL,"
                        + "key_type VARCHAR(16),"
                        + "operation VARCHAR(16) NOT NULL,"
                        + "value_preview VARCHAR(1024),"
                        + "value_bytes BIGINT,"
                        + "operator VARCHAR(64) NOT NULL,"
                        + "event_time TIMESTAMP NOT NULL"
                        + ")",
                "CREATE INDEX IF NOT EXISTS idx_key_history_profile_time ON key_history (profile_id, event_time)",
                "CREATE INDEX IF NOT EXISTS idx_key_history_key ON key_history (key_name)"
        );
    }

}
