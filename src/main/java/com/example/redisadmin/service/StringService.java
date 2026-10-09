package com.example.redisadmin.service;

import com.example.redisadmin.config.AppProperties;
import com.example.redisadmin.model.vo.StringValueVO;
import com.example.redisadmin.redis.ProfileConnectionManager;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import org.springframework.stereotype.Service;

/**
 * String 类型操作。
 * <p>大值防护：值 &gt; 1MB 时改用 {@code GETRANGE 0 65535} 预览，
 * 回 {@code truncated:true} + 完整 {@code length}（设计 §8）。</p>
 */
@Service
public class StringService {

    /**
     * 截断预览时取回的字节数上限。
     */
    private static final int PREVIEW_BYTES = 65536;

    private final ProfileConnectionManager connectionManager;
    private final KeyService keyService;
    private final KeyHistoryService historyService;
    private final AppProperties properties;

    public StringService(ProfileConnectionManager connectionManager,
                         KeyService keyService,
                         KeyHistoryService historyService,
                         AppProperties properties) {
        this.connectionManager = connectionManager;
        this.keyService = keyService;
        this.historyService = historyService;
        this.properties = properties;
    }

    public StringValueVO get(Long profileId, int db, String key) {
        keyService.checkKeyType(profileId, db, key, "STRING");
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);

        long length = commands.strlen(key);
        int maxBytes = properties.getLimits().getMaxValueBytes();
        if (length > maxBytes) {
            // 大 Value：只取预览，避免全量返回打爆浏览器
            String preview = commands.getrange(key, 0, PREVIEW_BYTES - 1L);
            return StringValueVO.builder()
                    .value(preview)
                    .length(length)
                    .truncated(true)
                    .build();
        }
        return StringValueVO.builder()
                .value(commands.get(key))
                .length(length)
                .truncated(false)
                .build();
    }

    public void set(Long profileId, int db, String key, String value, Integer ttlSeconds) {
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        commands.set(key, value);
        keyService.applyTtl(profileId, db, key, ttlSeconds);
        historyService.record(profileId, db, key, "STRING", KeyHistoryService.OP_SET, value, null);
    }

    public long append(Long profileId, int db, String key, String value) {
        keyService.checkKeyType(profileId, db, key, "STRING");
        RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, db);
        long length = commands.append(key, value);
        // APPEND 只记增量片段（完整值需额外 GET，此处不为一笔日志多一次往返）
        historyService.record(profileId, db, key, "STRING", KeyHistoryService.OP_APPEND, value, null);
        return length;
    }

    public void delete(Long profileId, int db, String key) {
        keyService.checkKeyType(profileId, db, key, "STRING");
        keyService.deleteKey(profileId, db, key);
    }
}
