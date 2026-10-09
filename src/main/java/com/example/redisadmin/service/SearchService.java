package com.example.redisadmin.service;

import com.example.redisadmin.config.AppProperties;
import com.example.redisadmin.exception.BizException;
import com.example.redisadmin.exception.ErrorCode;
import com.example.redisadmin.model.vo.KeyScanVO;
import com.example.redisadmin.model.vo.KeySearchHitVO;
import com.example.redisadmin.model.vo.KeySearchVO;
import com.example.redisadmin.redis.ProfileConnectionManager;
import com.example.redisadmin.redis.RedisProfile;
import io.lettuce.core.MapScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ValueScanCursor;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 实时数据检索：对<b>当前存活键空间</b>按 key / value 关键词检索。
 *
 * <h3>与操作日志的职责分离</h3>
 * 「什么时候动过这个 key」查 {@code GET /api/history/keys}（key_history 表）；
 * 「现在哪些 key 的值含某关键词」走本服务。二者不交叉：日志不承担实时检索职责，
 * 实时检索也不回溯历史。
 *
 * <h3>两条检索路径</h3>
 * <ol>
 *   <li><b>key 维度</b>：{@code SCAN MATCH *kw*} —— Redis 原生支持，成本与普通 SCAN 相当，
 *       无需逐 key 读值。语义为 <b>大小写敏感的 glob 匹配</b>（Redis 原生行为，不额外包装）。</li>
 *   <li><b>value 维度</b>：SCAN 出 key 后逐 key {@code TYPE} → 读值 → 包含匹配。
 *       Redis 无反向 value 索引，这是唯一可行路径，故必须靠<b>预算</b>控制
 *       （{@code search.max-keys-per-request}）。语义为 <b>大小写不敏感的子串匹配</b>。</li>
 * </ol>
 *
 * <h3>成本与语义诚实性</h3>
 * <ul>
 *   <li>单请求最多扫 {@code maxKeysPerRequest} 个 key；撞预算即返回<b>部分结果</b>
 *       （{@code exhausted=false}），不承诺全量</li>
 *   <li>容器类型每 key 只检查前 {@code maxElementsPerKey} 个元素（配合大 Key 防护）</li>
 *   <li>String 只匹配前 {@code max-value-bytes} 字节（既有口径）</li>
 *   <li>扫描期间键空间可能变化，结果<b>不是一致性快照</b></li>
 * </ul>
 * 集群模式逐 master SCAN，游标机制完全复用 {@link KeyService#scanKeys}
 * （{@code host|port|nodeCursor} 三段式，含该坑的规避）。
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    /**
     * 单次 SCAN 的 count 提示值（与 KeyService 默认一致）。
     */
    private static final int SCAN_COUNT = 200;

    /**
     * 命中片段的预览上限（字节）；纯展示用，不影响匹配结果。
     */
    private static final int PREVIEW_BYTES = 1024;

    private final ProfileConnectionManager connectionManager;
    private final KeyService keyService;
    private final int maxKeysPerRequest;
    private final int maxElementsPerKey;
    private final int maxPageSize;
    private final int maxValueBytes;

    public SearchService(ProfileConnectionManager connectionManager,
                         KeyService keyService,
                         AppProperties properties) {
        this.connectionManager = connectionManager;
        this.keyService = keyService;
        this.maxKeysPerRequest = properties.getSearch().getMaxKeysPerRequest();
        this.maxElementsPerKey = properties.getSearch().getMaxElementsPerKey();
        this.maxPageSize = properties.getSearch().getMaxPageSize();
        this.maxValueBytes = properties.getLimits().getMaxValueBytes();
    }

    /**
     * 按 key / value 关键词检索当前键空间。
     *
     * @param keyKeyword   key 关键词（走 SCAN MATCH，glob 且大小写敏感）；可空
     * @param valueKeyword value 关键词（大小写不敏感子串）；可空
     * @param limit        最多返回的命中条数，超出按 {@code search.max-page-size} 钳制
     * @throws BizException 40001（两个关键词都为空）/ 40401（profile 不存在）/ 集群 db>0
     */
    public KeySearchVO search(Long profileId, int db, String keyKeyword, String valueKeyword, Integer limit) {
        boolean byKey = hasText(keyKeyword);
        boolean byValue = hasText(valueKeyword);
        if (!byKey && !byValue) {
            // 两个关键词都空 = 全库 value 扫描，成本不可控；直接拒绝而非默默跑满预算
            throw BizException.of(ErrorCode.VALIDATION_ERROR,
                    "key 与 value 关键词至少提供一个：value 检索需逐 key 读值，全库扫描成本不可控");
        }

        RedisProfile profile = connectionManager.requireProfile(profileId);
        connectionManager.assertDbAllowed(profile, db);

        int maxHits = normalizeLimit(limit);
        int budget = maxKeysPerRequest;
        int scanned = 0;
        List<KeySearchHitVO> hits = new ArrayList<>();
        boolean exhausted;

        long start = System.currentTimeMillis();
        String cursor = "0";
        String matchPattern = byKey ? globPattern(keyKeyword.trim()) : null;
        String needle = byValue ? valueKeyword.trim().toLowerCase(Locale.ROOT) : null;

        // 遍历：单机与集群统一走 KeyService.scanKeys（内含 cluster 游标三段式与节点推进）
        outer:
        while (true) {
            if (scanned >= budget || hits.size() >= maxHits) {
                // 撞上扫描预算或命中数上限 → 部分结果（这是契约，不是错误）
                exhausted = false;
                break;
            }
            int count = Math.min(SCAN_COUNT, budget - scanned);
            KeyScanVO page = keyService.scanKeys(profileId, db, cursor, matchPattern, Math.max(1, count), null);

            for (String key : page.getKeys()) {
                if (scanned >= budget || hits.size() >= maxHits) {
                    exhausted = false;
                    break outer;
                }
                scanned++;
                if (!byValue) {
                    hits.add(describeKey(profileId, db, key, null, null));
                    continue;
                }
                ValueMatch match = matchValue(profileId, db, key, needle);
                if (match != null) {
                    hits.add(describeKey(profileId, db, key, match.type(), match.preview()));
                }
            }

            if (page.isExhausted()) {
                exhausted = true;
                break;
            }
            String next = page.getNextCursor();
            if (next == null || next.isBlank() || "0".equals(next.trim())) {
                exhausted = true;
                break;
            }
            cursor = next;
        }

        long elapsed = System.currentTimeMillis() - start;
        if (elapsed > 2000) {
            log.warn("实时检索慢操作: profileId={} db={} byKey={} byValue={} 扫描={} 命中={} 耗时={}ms",
                    profileId, db, byKey, byValue, scanned, hits.size(), elapsed);
        }

        return KeySearchVO.builder()
                .items(hits)
                .scanned(scanned)
                .exhausted(exhausted)
                .budget(budget)
                .build();
    }

    /**
     * 单 key 值匹配结果。type 在匹配时已查出，组装命中条目时透传，省一次 TYPE 命令。
     */
    private record ValueMatch(String type, String preview) {
    }

    /**
     * 逐 key 读值做包含匹配。命中返回类型与片段预览，未命中返回 null。
     * <p>单 key 异常（key 在扫描期间过期、类型突变等）不中断整体检索。</p>
     */
    private ValueMatch matchValue(Long profileId, int db, String key, String needle) {
        try {
            RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
            String type = commands.type(key);
            if (type == null) {
                return null;
            }
            String preview = switch (type.toUpperCase(Locale.ROOT)) {
                case "STRING" -> matchString(commands, key, needle);
                case "HASH" -> matchHash(commands, key, needle);
                case "LIST" -> matchList(commands, key, needle);
                case "SET" -> matchSet(commands, key, needle);
                case "ZSET" -> matchZSet(commands, key, needle);
                // none = 扫描期间已过期；其余类型不在 5 大基础类型范围内
                default -> null;
            };
            return preview == null ? null : new ValueMatch(type, preview);
        } catch (Exception e) {
            log.debug("检索跳过不可读 key: profileId={} db={} key={} cause={}",
                    profileId, db, key, e.getMessage());
            return null;
        }
    }

    /**
     * String：只匹配前 {@code max-value-bytes} 字节（与既有大值口径一致）。
     */
    private String matchString(RedisClusterCommands<String, String> commands, String key, String needle) {
        long length = commands.strlen(key);
        if (length == 0) {
            return null;
        }
        long window = Math.min(length, maxValueBytes);
        String value = commands.getrange(key, 0, window - 1);
        return contains(value, needle) ? preview(value) : null;
    }

    /**
     * Hash：字段名与字段值都参与匹配（任一命中即算命中）。
     * <p>属文档「值匹配」的合理超集：字段名也是用户可见数据，
     * 按名检索 Hash 的场景真实存在；已在本服务 javadoc 与 README 明示。</p>
     */
    private String matchHash(RedisClusterCommands<String, String> commands, String key, String needle) {
        MapScanCursor<String, String> cursor = commands.hscan(key, ScanCursor.of("0"),
                ScanArgs.Builder.limit(maxElementsPerKey));
        for (Map.Entry<String, String> entry : cursor.getMap().entrySet()) {
            if (contains(entry.getKey(), needle)) {
                return preview(entry.getKey());
            }
            if (contains(entry.getValue(), needle)) {
                return preview(entry.getValue());
            }
        }
        return null;
    }

    private String matchList(RedisClusterCommands<String, String> commands, String key, String needle) {
        List<String> items = commands.lrange(key, 0, maxElementsPerKey - 1L);
        for (String item : items) {
            if (contains(item, needle)) {
                return preview(item);
            }
        }
        return null;
    }

    private String matchSet(RedisClusterCommands<String, String> commands, String key, String needle) {
        ValueScanCursor<String> cursor = commands.sscan(key, ScanCursor.of("0"),
                ScanArgs.Builder.limit(maxElementsPerKey));
        for (String member : cursor.getValues()) {
            if (contains(member, needle)) {
                return preview(member);
            }
        }
        return null;
    }

    /**
     * ZSet：只匹配成员名（分数由 ZSet 专用端点检索，此处不参与字符串匹配）。
     */
    private String matchZSet(RedisClusterCommands<String, String> commands, String key, String needle) {
        // zrange 为 Redis 6.2 统一语法（旧变体 zrevrange/zrangebyscore 已 @Deprecated）
        List<String> members = commands.zrange(key, 0, maxElementsPerKey - 1L);
        for (String member : members) {
            if (contains(member, needle)) {
                return preview(member);
            }
        }
        return null;
    }

    /**
     * 组装命中条目：key / type / size / TTL + 命中片段。
     * size 与 TTL 各一次命令，量级为命中数（受 maxPageSize 封顶），不是扫描数。
     *
     * @param knownType value 维度匹配时已查过的类型；key 维度命中传 null（此处补查）
     */
    private KeySearchHitVO describeKey(Long profileId, int db, String key,
                                       String knownType, String matchedPreview) {
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        String type = knownType != null ? knownType : commands.type(key);
        long ttl = commands.ttl(key);
        long size = KeyService.sizeOf(commands, type, key);
        boolean truncated = matchedPreview != null
                && KeyService.exceedsUtf8Bytes(matchedPreview, PREVIEW_BYTES);
        return KeySearchHitVO.builder()
                .key(key)
                .type(type)
                .size(size)
                .ttlSeconds(ttl)
                .ttlFormat(KeyService.formatTtl(ttl))
                .matchedPreview(truncated
                        ? KeyService.truncateToUtf8Bytes(matchedPreview, PREVIEW_BYTES)
                        : matchedPreview)
                .truncated(truncated)
                .build();
    }

    /**
     * 关键词 → SCAN MATCH glob：{@code *kw*}，并转义 Redis glob 的通配元字符
     * （{@code \ * ? [ ]}）。{@code \} 是 Redis glob 的转义符。
     */
    static String globPattern(String keyword) {
        StringBuilder sb = new StringBuilder(keyword.length() + 4);
        sb.append('*');
        for (int i = 0; i < keyword.length(); i++) {
            char c = keyword.charAt(i);
            if (c == '\\' || c == '*' || c == '?' || c == '[' || c == ']') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('*');
        return sb.toString();
    }

    private static boolean contains(String value, String lowercaseNeedle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowercaseNeedle);
    }

    private static String preview(String value) {
        if (value == null) {
            return null;
        }
        return KeyService.exceedsUtf8Bytes(value, PREVIEW_BYTES)
                ? KeyService.truncateToUtf8Bytes(value, PREVIEW_BYTES)
                : value;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return Math.min(50, maxPageSize);
        }
        return Math.min(limit, maxPageSize);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}