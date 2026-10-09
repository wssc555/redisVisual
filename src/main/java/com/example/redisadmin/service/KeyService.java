package com.example.redisadmin.service;

import com.example.redisadmin.config.AppProperties;
import com.example.redisadmin.exception.BizException;
import com.example.redisadmin.exception.ErrorCode;
import com.example.redisadmin.model.vo.KeyDetailVO;
import com.example.redisadmin.model.vo.KeyScanVO;
import com.example.redisadmin.redis.DeployMode;
import com.example.redisadmin.redis.ProfileConnection;
import com.example.redisadmin.redis.ProfileConnectionManager;
import com.example.redisadmin.redis.RedisProfile;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 通用 Key 操作：SCAN 分页、详情（含 MEMORY USAGE）、删除、TTL、重命名。
 * <p>SCAN 是唯一遍历入口，<b>禁 KEYS</b>（存量与本设计均不提供 KEYS）。</p>
 */
@Service
public class KeyService {

    private static final Logger log = LoggerFactory.getLogger(KeyService.class);

    private static final int DEFAULT_SCAN_COUNT = 200;

    /**
     * SCAN 类型筛选白名单。与产品决策一致：仅 5 大基础类型
     * （stream/bitmap 等 K2 详情不支持，列表筛出来也点不开，直接拒绝）。
     */
    private static final Set<String> SUPPORTED_SCAN_TYPES =
            Set.of("string", "list", "hash", "set", "zset");

    private final ProfileConnectionManager connectionManager;
    private final KeyHistoryService historyService;
    private final AppProperties properties;

    public KeyService(ProfileConnectionManager connectionManager,
                      KeyHistoryService historyService,
                      AppProperties properties) {
        this.connectionManager = connectionManager;
        this.historyService = historyService;
        this.properties = properties;
    }

    /**
     * SCAN 分页。{@code count} 为提示值，不承诺精确页大小（Redis SCAN 语义）。
     * <p>游标不透明：单机/哨兵为数字字符串；集群为 {@code "host|port|nodeCursor"}。
     * 客户端原样回传即可，不应解析其内容。</p>
     * <p>{@code type} 非空时走 Redis 6.0+ 的 {@code SCAN ... TYPE}（服务端过滤，
     * 不做取回后过滤 —— 那会破坏游标分页语义）；空白视为不筛选。
     * 不在白名单 → 40001。Redis &lt; 6.0 不支持该选项，由 50001 兜底。</p>
     */
    public KeyScanVO scanKeys(Long profileId, int db, String cursor, String match, Integer count, String type) {
        RedisProfile profile = connectionManager.requireProfile(profileId);
        connectionManager.assertDbAllowed(profile, db);

        int limit = normalizeCount(count, DEFAULT_SCAN_COUNT, properties.getLimits().getMaxScanCount());
        ScanArgs args = ScanArgs.Builder.limit(limit);
        if (match != null && !match.isBlank()) {
            args.match(match);
        }
        if (type != null && !type.isBlank()) {
            String normalized = type.trim().toLowerCase(Locale.ROOT);
            if (!SUPPORTED_SCAN_TYPES.contains(normalized)) {
                throw new BizException(ErrorCode.VALIDATION_ERROR,
                        "不支持的类型筛选: " + type + "，仅支持 string/list/hash/set/zset");
            }
            // ⚠️ 把已含 COUNT/MATCH 的 args 作为 base 传入，否则 limit/match 整体丢失
            args = new TypeFilterScanArgs(args, normalized);
        }

        long start = System.currentTimeMillis();
        KeyScanVO result = profile.getMode() == DeployMode.CLUSTER
                ? clusterScan(profileId, cursor, args, limit)
                : nodeScan(profileId, db, cursor, args);
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed > 1000) {
            log.warn("SCAN 慢操作: profileId={} db={} count={} 耗时={}ms 返回={} 条",
                    profileId, db, limit, elapsed, result.getKeys().size());
        }
        return result;
    }

    /**
     * 单机 / 哨兵 SCAN：数字游标原样透传。
     */
    private KeyScanVO nodeScan(Long profileId, int db, String cursor, ScanArgs args) {
        KeyScanCursor<String> result = connectionManager.getSyncCommands(profileId, db)
                .scan(ScanCursor.of(normalizeNumericCursor(cursor)), args);
        return KeyScanVO.builder()
                .nextCursor(result.getCursor())
                .keys(result.getKeys())
                .exhausted(result.isFinished())
                .build();
    }

    /**
     * 集群键空间 SCAN：逐 master 节点推进。
     * <p>不能走 {@code RedisAdvancedClusterCommands.scan} —— 它只接受 lettuce 自产
     * {@code ClusterScanCursor}（{@code ClusterScanSupport.assertClusterScanCursor}），
     * 传入数字游标会抛 IllegalArgumentException。这里改为游标
     * {@code "host|port|nodeCursor"} 定位节点，单次请求内在节点间连续推进，
     * 累积至 {@code limit} 条或全部节点扫完（exhausted=true，游标归 "0"）。</p>
     */
    private KeyScanVO clusterScan(Long profileId, String cursor, ScanArgs args, int limit) {
        ProfileConnection connection = connectionManager.getConnection(profileId);
        List<RedisClusterNode> masters = connection.clusterMasters();
        if (masters.isEmpty()) {
            return KeyScanVO.builder().nextCursor("0").keys(List.of()).exhausted(true).build();
        }

        int[] position = decodeClusterCursor(cursor, masters);
        int index = position[0];
        String nodeCursor = String.valueOf(position[1]);

        List<String> keys = new ArrayList<>();
        while (index < masters.size()) {
            RedisClusterNode node = masters.get(index);
            KeyScanCursor<String> result = connection
                    .nodeConnection(node.getUri().getHost(), node.getUri().getPort())
                    .sync()
                    .scan(ScanCursor.of(nodeCursor), args);
            keys.addAll(result.getKeys());

            if (!result.isFinished()) {
                return KeyScanVO.builder()
                        .nextCursor(encodeClusterCursor(node, result.getCursor()))
                        .keys(keys)
                        .exhausted(false)
                        .build();
            }
            index++;
            nodeCursor = "0";
            if (!keys.isEmpty() && keys.size() >= limit && index < masters.size()) {
                return KeyScanVO.builder()
                        .nextCursor(encodeClusterCursor(masters.get(index), "0"))
                        .keys(keys)
                        .exhausted(false)
                        .build();
            }
        }
        return KeyScanVO.builder().nextCursor("0").keys(keys).exhausted(true).build();
    }

    /**
     * 集群游标解码：{@code "0"} 从头开始；{@code "host|port|nodeCursor"} 按节点地址重定位（拓扑刷新后序号可能漂移，按地址匹配更稳）。
     */
    private int[] decodeClusterCursor(String cursor, List<RedisClusterNode> masters) {
        if (cursor == null || cursor.isBlank() || "0".equals(cursor.trim())) {
            return new int[]{0, 0};
        }
        String[] parts = cursor.split("\\|");
        if (parts.length != 3) {
            throw BizException.of(ErrorCode.VALIDATION_ERROR, "集群 SCAN 游标格式非法: " + cursor);
        }
        int nodeCursor;
        try {
            nodeCursor = Integer.parseInt(parts[2].trim());
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.VALIDATION_ERROR, "集群 SCAN 节点游标必须为数字: " + cursor);
        }
        for (int i = 0; i < masters.size(); i++) {
            RedisClusterNode node = masters.get(i);
            if (node.getUri().getHost().equals(parts[0]) && node.getUri().getPort() == Integer.parseInt(parts[1])) {
                return new int[]{i, nodeCursor};
            }
        }
        // 节点已不在当前拓扑（下线/摘除）：从头开始，可能返回重复 key（SCAN 语义允许）
        log.info("集群 SCAN 游标指向的节点已不在拓扑中，回退从头扫描: {}", cursor);
        return new int[]{0, 0};
    }

    private String encodeClusterCursor(RedisClusterNode node, String nodeCursor) {
        return node.getUri().getHost() + "|" + node.getUri().getPort() + "|" + nodeCursor;
    }

    private static String normalizeNumericCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return "0";
        }
        String trimmed = cursor.trim();
        if (!trimmed.chars().allMatch(Character::isDigit)) {
            throw BizException.of(ErrorCode.VALIDATION_ERROR, "SCAN 游标必须为数字: " + cursor);
        }
        return trimmed;
    }

    /**
     * Key 详情：type / size / ttl / encoding / memoryUsageBytes。
     */
    public KeyDetailVO getKeyDetail(Long profileId, int db, String key) {
        RedisProfile profile = connectionManager.requireProfile(profileId);
        connectionManager.assertDbAllowed(profile, db);

        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        if (commands.exists(key) == 0) {
            throw BizException.of(ErrorCode.KEY_NOT_FOUND, key);
        }

        String type = commands.type(key);
        long ttl = commands.ttl(key);
        long size = sizeOf(commands, type, key);
        String encoding = commands.objectEncoding(key);
        Long memoryUsage = safeMemoryUsage(commands, key);

        return KeyDetailVO.builder()
                .key(key)
                .type(type)
                .size(size)
                .ttlSeconds(ttl)
                .ttlFormat(formatTtl(ttl))
                .encoding(encoding)
                .memoryUsageBytes(memoryUsage)
                .exists(true)
                .build();
    }

    public long deleteKey(Long profileId, int db, String key) {
        RedisProfile profile = connectionManager.requireProfile(profileId);
        connectionManager.assertDbAllowed(profile, db);
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        long deleted = commands.del(key);
        // 无鉴权体系下，审计日志是唯一事后追溯手段
        log.info("[AUDIT] profile={} db={} op=DEL key={} by=anonymous", profileId, db, key);
        if (deleted > 0) {
            historyService.record(profileId, db, key, null, KeyHistoryService.OP_DEL);
        }
        return deleted;
    }

    /**
     * 设置 TTL。{@code -1} = 永久（PERSIST），{@code -2} = key 不存在 → 40402。
     */
    public void setTtl(Long profileId, int db, String key, long ttlSeconds) {
        RedisProfile profile = connectionManager.requireProfile(profileId);
        connectionManager.assertDbAllowed(profile, db);
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);

        if (commands.exists(key) == 0) {
            throw BizException.of(ErrorCode.KEY_NOT_FOUND, key);
        }
        if (ttlSeconds == -1) {
            commands.persist(key);
        } else if (ttlSeconds >= 0) {
            commands.expire(key, ttlSeconds);
        } else {
            throw BizException.of(ErrorCode.VALIDATION_ERROR,
                    "ttlSeconds 非法（-1=永久，其他需 >=0），当前值: " + ttlSeconds);
        }
        log.info("[AUDIT] profile={} db={} op=SET_TTL key={} ttl={} by=anonymous", profileId, db, key, ttlSeconds);
    }

    /**
     * 重命名。目标已存在 → 40001（禁覆盖；RENAME 默认覆盖太危险）。
     * 用 RENAMENX 而非 RENAME 实现原子判存。
     */
    public void renameKey(Long profileId, int db, String key, String newKey) {
        RedisProfile profile = connectionManager.requireProfile(profileId);
        connectionManager.assertDbAllowed(profile, db);
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);

        if (commands.exists(key) == 0) {
            throw BizException.of(ErrorCode.KEY_NOT_FOUND, key);
        }
        if (key.equals(newKey)) {
            return;
        }
        Boolean renamed = commands.renamenx(key, newKey);
        if (!Boolean.TRUE.equals(renamed)) {
            throw BizException.of(ErrorCode.VALIDATION_ERROR, "目标 key 已存在，拒绝覆盖: " + newKey);
        }
        log.info("[AUDIT] profile={} db={} op=RENAME key={} newKey={} by=anonymous", profileId, db, key, newKey);
        // 记旧 key 一条，值预览附新 key 名（RENAME 的「当时值」即新名字）
        historyService.record(profileId, db, key, null, KeyHistoryService.OP_RENAME, newKey, null);
    }

    /**
     * 校验 key 存在且类型匹配，不匹配 → 40902。
     */
    public void checkKeyType(Long profileId, int db, String key, String expectedType) {
        RedisProfile profile = connectionManager.requireProfile(profileId);
        connectionManager.assertDbAllowed(profile, db);
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);

        if (commands.exists(key) == 0) {
            throw BizException.of(ErrorCode.KEY_NOT_FOUND, key);
        }
        String actual = commands.type(key);
        if (!expectedType.equalsIgnoreCase(actual)) {
            throw new BizException(ErrorCode.TYPE_MISMATCH, expectedType, actual);
        }
    }

    /**
     * 大 Key 防护：容器元素数超阈值且请求无分页参数 → 40001 拒绝。
     *
     * @param paginated 本次请求是否已带分页/游标参数
     */
    public void checkLargeKey(Long profileId, int db, String key, boolean paginated) {
        if (paginated) {
            return;
        }
        RedisProfile profile = connectionManager.requireProfile(profileId);
        connectionManager.assertDbAllowed(profile, db);
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);

        String type = commands.type(key);
        if (type == null || "none".equalsIgnoreCase(type)) {
            return;
        }
        long size = sizeOf(commands, type, key);
        int threshold = properties.getLimits().getLargeKeyThreshold();
        if (size > threshold) {
            throw BizException.of(ErrorCode.VALIDATION_ERROR,
                    "大 Key 拒绝无界全量请求：元素数 " + size + " > 阈值 " + threshold
                            + "，请使用带分页参数的接口（HSCAN/SSCAN/ZRANGE/LRANGE）");
        }
    }

    /**
     * 写入后按需施加 TTL（null = 不动 TTL）。
     */
    public void applyTtl(Long profileId, int db, String key, Integer ttlSeconds) {
        if (ttlSeconds != null && ttlSeconds > 0) {
            RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
            commands.expire(key, ttlSeconds);
        }
    }

    /**
     * UTF-8 字节长度是否超限（设计 §9.3 阈值按「字节」计）。
     * 仅在多字节歧义带内才做全量 getBytes，避免对大串的额外拷贝。
     */
    static boolean exceedsUtf8Bytes(String value, int maxBytes) {
        if (value == null) {
            return false;
        }
        if (value.length() > maxBytes) {
            return true;   // UTF-8 每字符至少 1 字节
        }
        if (value.length() <= maxBytes / 4) {
            return false;  // UTF-8 每字符至多 4 字节
        }
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes;
    }

    /**
     * 截断到 UTF-8 字节上限以内（收敛式收缩，不会切割多字节字符）。
     */
    static String truncateToUtf8Bytes(String value, int maxBytes) {
        int cut = Math.min(value.length(), maxBytes);
        while (cut > 0 && value.substring(0, cut).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes) {
            cut = cut * 3 / 4;
        }
        return value.substring(0, cut);
    }

    /**
     * 各类型的元素数 / 字节数。String 取 STRLEN，其余取容器基数。
     */
    static long sizeOf(RedisClusterCommands<String, String> commands, String type, String key) {
        if (type == null) {
            return 0;
        }
        return switch (type.toUpperCase()) {
            case "STRING" -> commands.strlen(key);
            case "LIST" -> commands.llen(key);
            case "HASH" -> commands.hlen(key);
            case "SET" -> commands.scard(key);
            case "ZSET" -> commands.zcard(key);
            default -> 0L;
        };
    }

    /**
     * MEMORY USAGE。走 Redis 默认 {@code SAMPLES 5} 采样口径，
     * 服务「大 Key 治理」；低版本 Redis 不支持该命令时降级为 null，不影响详情返回。
     */
    private Long safeMemoryUsage(RedisClusterCommands<String, String> commands, String key) {
        try {
            return commands.memoryUsage(key);
        } catch (Exception e) {
            log.debug("MEMORY USAGE 不可用（Redis 版本或权限限制）: {}", e.getMessage());
            return null;
        }
    }

    static int normalizeCount(Integer count, int fallback, int max) {
        if (count == null || count <= 0) {
            return fallback;
        }
        return Math.min(count, max);
    }

    static String formatTtl(long ttl) {
        if (ttl == -1) {
            return "永久";
        }
        if (ttl == -2) {
            return "不存在";
        }
        if (ttl < 60) {
            return ttl + "s";
        }
        if (ttl < 3600) {
            return (ttl / 60) + "m";
        }
        if (ttl < 86400) {
            return (ttl / 3600) + "h";
        }
        return (ttl / 86400) + "d";
    }
}
