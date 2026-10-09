package com.example.redisadmin.storage;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 存储方言抽象：把 DDL / DML 的方言差异收敛到实现类。
 * <p>幂等只靠版本号（见 {@link SchemaInitializer}），因此建索引等语句是否
 * 支持 {@code IF NOT EXISTS} 由各方言自行处理。</p>
 */
public interface StorageDialect {

    StorageType type();

    /**
     * 标识符加引号（MySQL 用反引号，PG/SQLite 用双引号；{@code key} 等保留字场景）。
     */
    String quoteIdent(String ident);

    /**
     * 版本表 DDL。
     */
    String createSchemaVersionTableSql();

    /**
     * V1：建 {@code redis_profile} 表。
     */
    String createRedisProfileTableSql();

    /**
     * V2：建 {@code key_history} 表 + 索引（返回语句列表，逐条执行）。
     * <p>历史事件日志：记录本平台执行的 key 写/删操作，供历史数据检索。</p>
     */
    List<String> createKeyHistorySqls();

    /**
     * 供版本读写默认实现使用的执行入口。
     */
    JdbcTemplate getJdbcTemplate();

    /**
     * 读当前版本号；无记录返回 0（三方言 SQL 相同，收敛为默认实现）。
     */
    default int queryCurrentVersion() {
        Integer version = getJdbcTemplate().queryForObject("SELECT MAX(version) FROM schema_version", Integer.class);
        return version == null ? 0 : version;
    }

    /**
     * 写入版本号（同上，三方言一致）。
     */
    default void insertVersion(int version) {
        getJdbcTemplate().update("INSERT INTO schema_version (version, applied_at) VALUES (?, ?)",
                version, java.time.LocalDateTime.now());
    }
}
