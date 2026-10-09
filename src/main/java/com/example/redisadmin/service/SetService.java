package com.example.redisadmin.service;

import com.example.redisadmin.model.vo.SetScanVO;
import com.example.redisadmin.redis.ProfileConnectionManager;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ValueScanCursor;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Set 类型操作。无序集合 → SSCAN 游标分页（默认 50，上限 500）。
 */
@Service
public class SetService {

    private static final int DEFAULT_SCAN_COUNT = 50;
    private static final int MAX_SCAN_COUNT = 500;

    private final ProfileConnectionManager connectionManager;
    private final KeyService keyService;
    private final KeyHistoryService historyService;

    public SetService(ProfileConnectionManager connectionManager, KeyService keyService,
                      KeyHistoryService historyService) {
        this.connectionManager = connectionManager;
        this.keyService = keyService;
        this.historyService = historyService;
    }

    public SetScanVO scan(Long profileId, int db, String key, long cursor, Integer count) {
        keyService.checkKeyType(profileId, db, key, "SET");
        // 大 Key 防护：未指定 count 视为无界全量请求
        keyService.checkLargeKey(profileId, db, key, count != null && count > 0);

        int limit = KeyService.normalizeCount(count, DEFAULT_SCAN_COUNT, MAX_SCAN_COUNT);

        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        ValueScanCursor<String> result = commands.sscan(key, ScanCursor.of(String.valueOf(cursor)),
                ScanArgs.Builder.limit(limit));
        List<String> members = result.getValues();
        long size = commands.scard(key);

        return SetScanVO.builder()
                .nextCursor(Long.parseLong(result.getCursor()))
                .members(members)
                .size(size)
                .build();
    }

    public long addMembers(Long profileId, int db, String key, List<String> members, Integer ttlSeconds) {
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        long added = commands.sadd(key, members.toArray(new String[0]));
        keyService.applyTtl(profileId, db, key, ttlSeconds);
        // added==0 表示成员已全部存在，无实际变更，不记日志（与 SREM/HDEL/ZREM 口径一致）
        if (added > 0) {
            historyService.record(profileId, db, key, "SET", KeyHistoryService.OP_SADD,
                    members == null ? null : String.join(",", members), null);
        }
        return added;
    }

    public boolean isMember(Long profileId, int db, String key, String member) {
        keyService.checkKeyType(profileId, db, key, "SET");
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        return commands.sismember(key, member);
    }

    public long removeMember(Long profileId, int db, String key, String member) {
        keyService.checkKeyType(profileId, db, key, "SET");
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        long removed = commands.srem(key, member);
        if (removed > 0) {
            historyService.record(profileId, db, key, "SET", KeyHistoryService.OP_SREM, member, null);
        }
        return removed;
    }
}
