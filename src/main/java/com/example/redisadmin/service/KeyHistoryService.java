package com.example.redisadmin.service;

import com.example.redisadmin.config.AppProperties;
import com.example.redisadmin.exception.BizException;
import com.example.redisadmin.exception.ErrorCode;
import com.example.redisadmin.model.vo.KeyHistoryPageVO;
import com.example.redisadmin.model.vo.KeyHistoryVO;
import com.example.redisadmin.redis.ProfileConnectionManager;
import com.example.redisadmin.storage.KeyHistoryRow;
import com.example.redisadmin.storage.KeyHistoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 操作日志服务：写路径旁路埋点的唯一入口 + 历史分页查询 + 过期清理。
 *
 * <h3>覆盖边界（产品文档与 API 注释双处明示）</h3>
 * 只记录<b>经本平台</b>执行的写/删操作。外部客户端（redis-cli、业务应用）的变更
 * 与 Redis 端被动 TTL 过期<b>不在其中</b>——Redis 原生不提供 key 创建/删除时间元数据，
 * 无可回溯途径。日志不是全量事实，是平台侧操作留痕。
 *
 * <h3>operation 语义</h3>
 * 直接落 Redis 命令名（SET / DEL / RENAME / HSET / ...），常量见本类。
 * 刻意<b>不做 CREATE/UPDATE 判定</b>：那需要每笔写操作多一次 {@code EXISTS} 预检，
 * 写路径零额外 Redis 命令是简化方案的核心收益。「删除记录」按操作名筛 {@code DEL} 即可。
 *
 * <h3>可靠性</h3>
 * 写入失败<b>不阻断业务主流程</b>（catch + ERROR 日志）——旁路审计不应把成功的写操作变成失败。
 * 同步落库（单行 INSERT，&lt;1ms）而非异步：日志按 event_time 有序，
 * 异步化会引入乱序与队列丢弃语义，收益不抵复杂度。
 */
@Service
public class KeyHistoryService {

    private static final Logger log = LoggerFactory.getLogger(KeyHistoryService.class);

    // operation 常量即 Redis 命令名，长度与列宽 VARCHAR(16) 匹配

    public static final String OP_SET = "SET";
    public static final String OP_APPEND = "APPEND";
    public static final String OP_DEL = "DEL";
    public static final String OP_RENAME = "RENAME";
    public static final String OP_LPUSH = "LPUSH";
    public static final String OP_RPUSH = "RPUSH";
    public static final String OP_LSET = "LSET";
    public static final String OP_LREM = "LREM";
    public static final String OP_LPOP = "LPOP";
    public static final String OP_RPOP = "RPOP";
    public static final String OP_HSET = "HSET";
    public static final String OP_HMSET = "HMSET";
    public static final String OP_HDEL = "HDEL";
    public static final String OP_SADD = "SADD";
    public static final String OP_SREM = "SREM";
    public static final String OP_ZADD = "ZADD";
    public static final String OP_ZINCRBY = "ZINCRBY";
    public static final String OP_ZREM = "ZREM";

    /**
     * 无鉴权体系，操作者恒为匿名（与 [AUDIT] 日志口径一致）。
     */
    private static final String OPERATOR_ANONYMOUS = "anonymous";

    private static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * {@code key_name} 列宽（字符），与三方言 DDL 对齐。
     * MySQL/PG 强制 VARCHAR 长度：超长 key 的 INSERT 在 strict mode 下报错、
     * 被 {@link #record} 吞掉 → 审计行静默丢失。超长 key 是病态场景，
     * 截断保行优于丢行（{@code value_bytes} 记的是 value 长度，不受影响）。
     */
    private static final int KEY_COLUMN_CHARS = 512;

    /**
     * {@code value_preview} 列宽（字符），同上对齐 DDL；previewBytes 配置超此值时钳制。
     */
    private static final int PREVIEW_COLUMN_CHARS = 1024;

    private final KeyHistoryStore store;
    private final ProfileConnectionManager connectionManager;
    private final int previewBytes;
    private final int maxPageSize;
    private final int retentionDays;

    public KeyHistoryService(KeyHistoryStore store,
                             ProfileConnectionManager connectionManager,
                             AppProperties properties) {
        this.store = store;
        this.connectionManager = connectionManager;
        // 钳制到 DDL 列宽（MySQL/PG 强制 VARCHAR 长度）：配置超限时预览在写库侧必然失败
        int configured = properties.getHistory().getPreviewBytes();
        this.previewBytes = Math.min(configured, PREVIEW_COLUMN_CHARS);
        if (configured > PREVIEW_COLUMN_CHARS) {
            log.warn("history.preview-bytes={} 超过 value_preview 列宽 {}，已钳制（MySQL/PG 方言下列宽是硬限制）",
                    configured, PREVIEW_COLUMN_CHARS);
        }
        this.maxPageSize = properties.getHistory().getMaxPageSize();
        this.retentionDays = properties.getHistory().getRetentionDays();
    }

    /**
     * 记录一次写/删操作。<b>任何失败都被吞掉并记 ERROR 日志</b>，绝不向调用方抛出——
     * 审计旁路不应让业务写操作失败。
     *
     * @param keyType   Redis 类型（大写）；删除类可传 null（key 已消失，不再查 TYPE 省一次往返）
     * @param value     值快照原文（容器传成员/字段拼接摘要）；无值快照传 null
     * @param eventTime 事件时间；传 null 取当前时间
     */
    public void record(Long profileId, int db, String key, String keyType,
                       String operation, String value, LocalDateTime eventTime) {
        try {
            KeyHistoryRow row = new KeyHistoryRow();
            row.setProfileId(profileId);
            row.setDb(db);
            row.setKeyName(truncateKeyName(key));
            row.setKeyType(keyType);
            row.setOperation(operation);
            row.setOperator(OPERATOR_ANONYMOUS);
            row.setEventTime(eventTime == null ? LocalDateTime.now() : eventTime);
            if (value != null) {
                row.setValueBytes(utf8Length(value));
                row.setValuePreview(KeyService.truncateToUtf8Bytes(value, previewBytes));
            }
            store.append(row);
        } catch (Exception e) {
            log.error("操作日志写入失败（已忽略，不影响业务）: profileId={} db={} op={} key={} cause={}",
                    profileId, db, operation, key, rootMessage(e));
        }
    }

    public void record(Long profileId, int db, String key, String keyType, String operation) {
        record(profileId, db, key, keyType, operation, null, null);
    }

    /**
     * 组合条件分页查询，全部参数可选、AND 组合。
     * <p>key / value 为大小写不敏感子串匹配（LIKE + LOWER，通配符已转义）。</p>
     *
     * @param startTime 起始时间（含）；@param endTime 结束时间（含）
     */
    public KeyHistoryPageVO query(Long profileId, Integer db, String key, String value,
                                  String operation, LocalDateTime startTime, LocalDateTime endTime,
                                  Integer page, Integer pageSize) {
        if (profileId != null) {
            // 存在性校验：profileId 传错应报 40401，而不是静默返回空列表
            connectionManager.requireProfile(profileId);
        }
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw BizException.of(ErrorCode.VALIDATION_ERROR,
                    "startTime 不能晚于 endTime: " + startTime + " > " + endTime);
        }

        int safePage = (page == null || page < 1) ? 1 : page;
        int safePageSize = (pageSize == null || pageSize < 1) ? DEFAULT_PAGE_SIZE : Math.min(pageSize, maxPageSize);

        KeyHistoryStore.HistoryFilter filter = new KeyHistoryStore.HistoryFilter(
                profileId, db, likePattern(key), likePattern(value),
                normalizeOperation(operation), startTime, endTime);

        long total = store.count(filter);
        List<KeyHistoryVO> items = store.query(filter, (long) (safePage - 1) * safePageSize, safePageSize)
                .stream()
                .map(KeyHistoryService::toVO)
                .toList();

        return KeyHistoryPageVO.builder()
                .total(total)
                .page(safePage)
                .pageSize(safePageSize)
                .items(items)
                .build();
    }

    /**
     * 过期清理：删除 event_time 早于 {@code now - retentionDays} 的行。
     * <p>{@code initialDelay} 取与周期同值，让首次清理发生在启动一个周期之后——
     * 否则定时任务可能在 {@code SchemaInitializer} 建表之前就触发（v2 表尚不存在）。
     * 注意该前提依赖「周期 ≥ 迁移耗时」：若运维把 {@code purge-interval-ms} 配得极小
     * （如秒级），首次清理仍可能撞在迁移前——有 try/catch 兜底，只丢一轮清理不致命。</p>
     */
    @Scheduled(fixedDelayString = "${redis-admin.history.purge-interval-ms:86400000}",
            initialDelayString = "${redis-admin.history.purge-interval-ms:86400000}")
    public void purge() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
            int removed = store.purgeBefore(cutoff);
            if (removed > 0) {
                log.info("操作日志清理完成: 保留 {} 天，删除 {} 条（早于 {}）", retentionDays, removed, cutoff);
            }
        } catch (Exception e) {
            log.error("操作日志清理失败（不影响业务）: cause={}", rootMessage(e));
        }
    }

    private static KeyHistoryVO toVO(KeyHistoryRow row) {
        return KeyHistoryVO.builder()
                .id(row.getId())
                .profileId(row.getProfileId())
                .db(row.getDb())
                .keyName(row.getKeyName())
                .keyType(row.getKeyType())
                .operation(row.getOperation())
                .valuePreview(row.getValuePreview())
                .valueBytes(row.getValueBytes())
                .operator(row.getOperator())
                .eventTime(row.getEventTime())
                .build();
    }

    /**
     * 子串关键词 → LIKE pattern：{@code %kw%}，并转义 {@code ! % _} 三个通配符
     * （{@code ESCAPE '!'}，与 {@link KeyHistoryStore#appendWhere} 约定一致；
     * 不用反斜杠——MySQL 把 {@code '\'} 解析为字面量转义，{@code ESCAPE '\'} 是语法错误）。
     * 空串视为不过滤（前端传空参数不应变成「匹配空串」）。
     */
    private static String likePattern(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String trimmed = keyword.trim();
        StringBuilder sb = new StringBuilder(trimmed.length() + 8);
        sb.append('%');
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '!' || c == '%' || c == '_') {
                sb.append('!');
            }
            sb.append(c);
        }
        sb.append('%');
        return sb.toString();
    }

    /**
     * operation 过滤值归一：列中恒为大写命令名常量，用户传 {@code del} 也应能命中。
     */
    private static String normalizeOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            return null;
        }
        return operation.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * key 名按 {@code key_name} 列宽截断（VARCHAR 按字符计），防 MySQL/PG strict mode 下 INSERT 失败丢审计行。
     */
    private static String truncateKeyName(String key) {
        if (key == null || key.length() <= KEY_COLUMN_CHARS) {
            return key;
        }
        return key.substring(0, KEY_COLUMN_CHARS);
    }

    /**
     * UTF-8 字节长度，<b>不分配字节数组</b>。
     * 直接 {@code getBytes(UTF_8)} 在 1MB 级 value 上会产生等量副本，
     * 而埋点在每个写操作路径上，必须零额外拷贝。
     */
    static long utf8Length(String s) {
        long length = 0;
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c < 0x80) {
                i++;
                length += 1;
            } else if (c < 0x800) {
                i++;
                length += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < s.length()
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                i += 2;
                length += 4;   // 代理对 = 一个 4 字节码点
            } else {
                // 落单代理字符按 getBytes(UTF_8) 的实际编码 '?'（1 字节）计
                i++;
                length += 1;
            }
        }
        return length;
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}