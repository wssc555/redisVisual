package com.example.redisadmin.storage;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * {@code key_history} 表行：本平台执行的一次 key 写/删事件。
 * <p>{@code operation} <b>直接使用 Redis 命令语义</b>（{@code SET} / {@code DEL} / {@code RENAME} /
 * {@code HSET} / {@code LPUSH} / {@code ZADD} ...），常量见 {@code KeyHistoryService}。
 * 刻意<b>不做 CREATE/UPDATE 语义判定</b>——那需要每笔写操作多一次 {@code EXISTS} 预检；
 * 「删除记录」按操作名筛 {@code DEL} 即可。</p>
 * <p>覆盖边界：<b>只记录经本平台发生的操作</b>，外部客户端的变更与 Redis 端被动过期
 * 不在其中（Redis 原生不提供 key 创建/删除时间元数据）。</p>
 */
@Data
public class KeyHistoryRow {
    private Long id;
    private Long profileId;
    private int db;
    private String keyName;
    private String keyType;
    private String operation;
    /**
     * 值预览（截断至配置上限；容器类型为成员/字段的拼接摘要）。
     */
    private String valuePreview;
    /**
     * 原始值 UTF-8 字节长度（截断前）。
     */
    private Long valueBytes;
    private String operator;
    private LocalDateTime eventTime;
}
