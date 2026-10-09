package com.example.redisadmin.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 操作日志条目（{@code key_history} 行对外投影）。
 * <p>与存储层 {@code KeyHistoryRow} 字段同构但独立：避免把持久化实体直接暴露给 API，
 * 也让「{@code valuePreview} 为 null（删除类操作无值快照）」的语义在契约里显式可见。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyHistoryVO {

    private Long id;
    private Long profileId;
    private Integer db;
    private String keyName;
    private String keyType;
    /**
     * Redis 命令语义：SET / DEL / RENAME / HSET / LPUSH / ZADD ...
     */
    private String operation;
    /**
     * 值预览（已按 history.preview-bytes 截断）；删除类操作为 null。
     */
    private String valuePreview;
    /**
     * 原始值 UTF-8 字节长度（截断前）；无值快照时为 null。
     */
    private Long valueBytes;
    /**
     * 恒为 anonymous（无鉴权体系，与 [AUDIT] 日志口径一致）。
     */
    private String operator;
    private LocalDateTime eventTime;
}