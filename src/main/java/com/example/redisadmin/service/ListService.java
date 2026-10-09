package com.example.redisadmin.service;

import com.example.redisadmin.config.AppProperties;
import com.example.redisadmin.exception.BizException;
import com.example.redisadmin.exception.ErrorCode;
import com.example.redisadmin.model.vo.ListRangeVO;
import com.example.redisadmin.redis.ProfileConnectionManager;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * List 类型操作。分页走 LRANGE 索引区间，配合 LLEN 总数。
 * <p>按索引删除用 {@code LSET 哨兵值 + LREM} 两步实现（Redis 无原子单指令），
 * msg 中明示非原子语义。</p>
 */
@Service
public class ListService {

    private final ProfileConnectionManager connectionManager;
    private final KeyService keyService;
    private final KeyHistoryService historyService;
    private final AppProperties properties;

    public ListService(ProfileConnectionManager connectionManager, KeyService keyService,
                       KeyHistoryService historyService, AppProperties properties) {
        this.connectionManager = connectionManager;
        this.keyService = keyService;
        this.historyService = historyService;
        this.properties = properties;
    }

    /**
     * LRANGE 索引区间读取。区间长度超上限 → 40001。
     */
    public ListRangeVO getRange(Long profileId, int db, String key, long start, long end) {
        keyService.checkKeyType(profileId, db, key, "LIST");
        // 大 Key 防护：未显式给出 end（-1 表示「到末尾」）视为无界全量请求
        boolean bounded = end >= 0;
        keyService.checkLargeKey(profileId, db, key, bounded);

        long span = end - start + 1;
        int maxRange = properties.getLimits().getMaxListRange();
        if (span > maxRange) {
            throw BizException.of(ErrorCode.VALIDATION_ERROR,
                    "单次读取区间过大（" + span + " > " + maxRange + "），请缩小 start~end 范围");
        }

        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        long total = commands.llen(key);
        List<String> items = commands.lrange(key, start, end);
        return ListRangeVO.builder().total(total).items(items).build();
    }

    public long lpush(Long profileId, int db, String key, List<String> values, Integer ttlSeconds) {
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        long length = commands.lpush(key, values.toArray(new String[0]));
        keyService.applyTtl(profileId, db, key, ttlSeconds);
        historyService.record(profileId, db, key, "LIST", KeyHistoryService.OP_LPUSH,
                joinForPreview(values), null);
        return length;
    }

    public long rpush(Long profileId, int db, String key, List<String> values, Integer ttlSeconds) {
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        long length = commands.rpush(key, values.toArray(new String[0]));
        keyService.applyTtl(profileId, db, key, ttlSeconds);
        historyService.record(profileId, db, key, "LIST", KeyHistoryService.OP_RPUSH,
                joinForPreview(values), null);
        return length;
    }

    public void setByIndex(Long profileId, int db, String key, long index, String value) {
        keyService.checkKeyType(profileId, db, key, "LIST");
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        commands.lset(key, index, value);
        historyService.record(profileId, db, key, "LIST", KeyHistoryService.OP_LSET,
                index + "=" + value, null);
    }

    /**
     * 按索引删除：{@code LSET 哨兵值 + LREM} 两步。
     * <p><b>非原子</b>：并发修改下列表时索引可能漂移，前端应重拉区间。</p>
     */
    public void deleteByIndex(Long profileId, int db, String key, long index) {
        keyService.checkKeyType(profileId, db, key, "LIST");
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);

        long total = commands.llen(key);
        if (index < 0) {
            index = total + index;
        }
        if (index < 0 || index >= total) {
            throw BizException.of(ErrorCode.VALIDATION_ERROR,
                    "索引越界: " + index + "（列表长度 " + total + "）");
        }

        String tombstone = "__REDIS_ADMIN_TOMBSTONE__" + System.nanoTime();
        commands.lset(key, index, tombstone);
        long removed = commands.lrem(key, 1, tombstone);
        if (removed == 0) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "按索引删除失败：列表已被并发修改，请重试");
        }
        // 被删元素的真实值在覆盖前已不可知（此处只知索引），预览记索引
        historyService.record(profileId, db, key, "LIST", KeyHistoryService.OP_LREM,
                "index=" + index, null);
    }

    public String pop(Long profileId, int db, String key, String direction) {
        keyService.checkKeyType(profileId, db, key, "LIST");
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        boolean right = "RIGHT".equalsIgnoreCase(direction);
        String popped = right ? commands.rpop(key) : commands.lpop(key);
        // POP 返回值即被移除的元素，是唯一能拿到「当时的值」的机会
        if (popped != null) {
            historyService.record(profileId, db, key, "LIST",
                    right ? KeyHistoryService.OP_RPOP : KeyHistoryService.OP_LPOP, popped, null);
        }
        return popped;
    }

    /**
     * 容器写入的值预览：成员拼接摘要（截断由 KeyHistoryService 统一按配置执行）。
     */
    private static String joinForPreview(List<String> values) {
        return values == null ? null : String.join(",", values);
    }
}
