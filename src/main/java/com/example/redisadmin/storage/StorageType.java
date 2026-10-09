package com.example.redisadmin.storage;

/**
 * 存储方言。支持 SQLite（默认零配置）/ MySQL / PostgreSQL。
 */
public enum StorageType {
    SQLITE,
    MYSQL,
    POSTGRES;

    public static StorageType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return SQLITE;
        }
        try {
            return StorageType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的存储方言: " + raw + "（可选 SQLITE / MYSQL / POSTGRES）");
        }
    }
}
