package com.example.redisadmin.service;

import com.example.redisadmin.config.AppProperties;
import com.example.redisadmin.exception.BizException;
import com.example.redisadmin.exception.ErrorCode;
import com.example.redisadmin.model.vo.ZSetMemberVO;
import com.example.redisadmin.model.vo.ZSetRangeVO;
import com.example.redisadmin.model.vo.ZSetRankVO;
import com.example.redisadmin.redis.ProfileConnectionManager;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.ZAddArgs;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ZSet 类型操作。有序集合 → 支持索引/分数双维分页：
 * <ul>
 *   <li>纯分页：{@code ZRANGE offset count}（{@code page}/{@code pageSize}）</li>
 *   <li>分数区间 + LIMIT：{@code ZRANGEBYSCORE min max LIMIT offset count}（{@code min}/{@code max}）</li>
 * </ul>
 * {@code total} 一律用 ZCARD 真值，不用 {@code withScores} 的结果长度。
 */
@Service
public class ZSetService {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ProfileConnectionManager connectionManager;
    private final KeyService keyService;
    private final KeyHistoryService historyService;
    private final AppProperties properties;

    public ZSetService(ProfileConnectionManager connectionManager, KeyService keyService,
                       KeyHistoryService historyService, AppProperties properties) {
        this.connectionManager = connectionManager;
        this.keyService = keyService;
        this.historyService = historyService;
        this.properties = properties;
    }

    /**
     * 分页读取。{@code min}/{@code max} 至少给一个时走分数区间 + LIMIT，
     * 否则走 {@code ZRANGE} 索引分页。
     */
    public ZSetRangeVO getRange(Long profileId, int db, String key, int page, int pageSize,
                                String order, Double min, Double max) {
        keyService.checkKeyType(profileId, db, key, "ZSET");

        int maxPageSize = properties.getLimits().getMaxZSetPageSize();
        if (page < 1) {
            page = 1;
        }
        if (pageSize <= 0) {
            pageSize = DEFAULT_PAGE_SIZE;
        }
        if (pageSize > maxPageSize) {
            pageSize = maxPageSize;
        }
        long offset = (long) (page - 1) * pageSize;
        boolean desc = "DESC".equalsIgnoreCase(order);
        boolean byScore = min != null || max != null;

        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        long total = commands.zcard(key);

        List<ScoredValue<String>> values;
        if (byScore) {
            double rangeMin = min != null ? min : Double.NEGATIVE_INFINITY;
            double rangeMax = max != null ? max : Double.POSITIVE_INFINITY;
            // 分数区间语义：min/max 是筛选条件而非页边界，故 offset 恒为 0
            values = desc
                    ? commands.zrevrangebyscoreWithScores(key, rangeMin, rangeMax, 0, pageSize)
                    : commands.zrangebyscoreWithScores(key, rangeMin, rangeMax, 0, pageSize);
        } else {
            long start = offset;
            long stop = offset + pageSize - 1;
            values = desc
                    ? commands.zrevrangeWithScores(key, start, stop)
                    : commands.zrangeWithScores(key, start, stop);
        }

        List<ZSetMemberVO> items = values.stream()
                .map(sv -> ZSetMemberVO.builder().member(sv.getValue()).score(sv.getScore()).build())
                .toList();
        return ZSetRangeVO.builder().total(total).items(items).build();
    }

    public long addMember(Long profileId, int db, String key, String member, double score, Integer ttlSeconds) {
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        // NX：member 已存在时不覆盖分数，返回 0
        long added = commands.zadd(key, ZAddArgs.Builder.nx(), score, member);
        keyService.applyTtl(profileId, db, key, ttlSeconds);
        // added==0 表示 NX 命中未写入，不记日志（无实际变更）
        if (added > 0) {
            historyService.record(profileId, db, key, "ZSET", KeyHistoryService.OP_ZADD,
                    member + "=" + score, null);
        }
        return added;
    }

    /**
     * ZINCRBY 幂等：同一 member 重复调用是累加语义，不会报错。
     */
    public double incrementScore(Long profileId, int db, String key, String member, double delta) {
        keyService.checkKeyType(profileId, db, key, "ZSET");
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        double newScore = commands.zincrby(key, delta, member);
        // 记变更后的分数（ZINCRBY 的返回即新分），比记 delta 更有检索价值
        historyService.record(profileId, db, key, "ZSET", KeyHistoryService.OP_ZINCRBY,
                member + "=" + newScore, null);
        return newScore;
    }

    /**
     * 覆盖式设置分数（XX：member 不存在时返回 0，不创建）。
     */
    public long updateScore(Long profileId, int db, String key, String member, double score) {
        keyService.checkKeyType(profileId, db, key, "ZSET");
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        long updated = commands.zadd(key, ZAddArgs.Builder.xx(), score, member);
        if (updated > 0) {
            historyService.record(profileId, db, key, "ZSET", KeyHistoryService.OP_ZADD,
                    member + "=" + score, null);
        }
        return updated;
    }

    public long removeMember(Long profileId, int db, String key, String member) {
        keyService.checkKeyType(profileId, db, key, "ZSET");
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        long removed = commands.zrem(key, member);
        if (removed > 0) {
            historyService.record(profileId, db, key, "ZSET", KeyHistoryService.OP_ZREM, member, null);
        }
        return removed;
    }

    public ZSetRankVO getRank(Long profileId, int db, String key, String member, String order) {
        keyService.checkKeyType(profileId, db, key, "ZSET");
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);

        Long rank = "DESC".equalsIgnoreCase(order)
                ? commands.zrevrank(key, member)
                : commands.zrank(key, member);
        if (rank == null) {
            throw BizException.of(ErrorCode.KEY_NOT_FOUND, key + " 中不存在成员: " + member);
        }
        return ZSetRankVO.builder().rank(rank).score(commands.zscore(key, member)).build();
    }
}
