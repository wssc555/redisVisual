package com.example.redisadmin.service;

import com.example.redisadmin.config.AppProperties;
import com.example.redisadmin.model.vo.HashFieldVO;
import com.example.redisadmin.model.vo.HashScanVO;
import com.example.redisadmin.redis.ProfileConnectionManager;
import io.lettuce.core.MapScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hash 类型操作。无序集合 → HSCAN 游标分页。
 * <p>字段值 &gt; 1MB 时取回后截断（HGET 无 stride 类命令，只能 HSCAN 后截断）。</p>
 */
@Service
public class HashService {

    private static final int DEFAULT_SCAN_COUNT = 50;

    private final ProfileConnectionManager connectionManager;
    private final KeyService keyService;
    private final KeyHistoryService historyService;
    private final AppProperties properties;

    public HashService(ProfileConnectionManager connectionManager, KeyService keyService,
                       KeyHistoryService historyService, AppProperties properties) {
        this.connectionManager = connectionManager;
        this.keyService = keyService;
        this.historyService = historyService;
        this.properties = properties;
    }

    public HashScanVO scan(Long profileId, int db, String key, long cursor, String match, Integer count) {
        keyService.checkKeyType(profileId, db, key, "HASH");
        // 大 Key 防护：未指定 count 视为无界全量请求（cursor 只是起点，不构成边界）
        keyService.checkLargeKey(profileId, db, key, count != null && count > 0);

        int limit = KeyService.normalizeCount(count, DEFAULT_SCAN_COUNT, properties.getLimits().getMaxScanCount());

        ScanArgs args = ScanArgs.Builder.limit(limit);
        if (match != null && !match.isBlank() && !"*".equals(match)) {
            args.match(match);
        }

        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        MapScanCursor<String, String> result = commands.hscan(key, ScanCursor.of(String.valueOf(cursor)), args);

        int maxBytes = properties.getLimits().getMaxValueBytes();
        List<HashFieldVO> fields = new ArrayList<>(result.getMap().size());
        for (Map.Entry<String, String> entry : result.getMap().entrySet()) {
            String value = entry.getValue();
            boolean truncated = KeyService.exceedsUtf8Bytes(value, maxBytes);
            fields.add(HashFieldVO.builder()
                    .field(entry.getKey())
                    .value(truncated ? KeyService.truncateToUtf8Bytes(value, maxBytes) : value)
                    .truncated(truncated)
                    .build());
        }
        return HashScanVO.builder()
                .nextCursor(Long.parseLong(result.getCursor()))
                .fields(fields)
                .build();
    }

    public String getField(Long profileId, int db, String key, String field) {
        keyService.checkKeyType(profileId, db, key, "HASH");
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        return commands.hget(key, field);
    }

    public void setField(Long profileId, int db, String key, String field, String value, Integer ttlSeconds) {
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        commands.hset(key, field, value);
        keyService.applyTtl(profileId, db, key, ttlSeconds);
        historyService.record(profileId, db, key, "HASH", KeyHistoryService.OP_HSET,
                field + "=" + value, null);
    }

    public void batchSet(Long profileId, int db, String key, Map<String, String> entries, Integer ttlSeconds) {
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        commands.hmset(key, entries);
        keyService.applyTtl(profileId, db, key, ttlSeconds);
        // 批量写入只记一条摘要（逐字段记会在大 Hash 上放大日志量）
        historyService.record(profileId, db, key, "HASH", KeyHistoryService.OP_HMSET,
                summarizeEntries(entries), null);
    }

    public long deleteField(Long profileId, int db, String key, String field) {
        keyService.checkKeyType(profileId, db, key, "HASH");
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        long removed = commands.hdel(key, field);
        if (removed > 0) {
            historyService.record(profileId, db, key, "HASH", KeyHistoryService.OP_HDEL, field, null);
        }
        return removed;
    }

    /**
     * 字段值摘要：{@code f1=v1, f2=v2}（截断由 KeyHistoryService 统一执行）。
     */
    private static String summarizeEntries(Map<String, String> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        return entries.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "," + b)
                .orElse(null);
    }
}
