package com.example.redisadmin.redis;

import com.example.redisadmin.config.AppProperties;
import com.example.redisadmin.exception.BizException;
import com.example.redisadmin.exception.ErrorCode;
import com.example.redisadmin.storage.ProfileStore;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理全部 {@link ProfileConnection}：Caffeine 真 LRU + 空闲超时双保险，
 * 兼心跳探活与运行时状态维护。
 * <p>启动不预连：{@code StartupValidator} 只读汇报可达性，不阻塞不退出（对齐 §6.3）。</p>
 * <p>运行时状态（在线/离线）只在内存，心跳<b>不写库</b>。</p>
 */
@Service
public class ProfileConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(ProfileConnectionManager.class);

    private final RedisClientFactory clientFactory;
    private final RedisTimeouts timeouts;
    private final ProfileStore profileStore;
    private final int maxConnectionsPerProfile;

    /**
     * profileId → 连接集。真 LRU + expireAfterAccess 双保险。
     */
    private final Cache<Long, ProfileConnection> connections;
    /**
     * profileId → 运行时状态（不落库）。
     */
    private final Map<Long, ProfileState> states = new ConcurrentHashMap<>();

    public ProfileConnectionManager(RedisClientFactory clientFactory,
                                    RedisTimeouts timeouts,
                                    ProfileStore profileStore,
                                    AppProperties properties) {
        this.clientFactory = clientFactory;
        this.timeouts = timeouts;
        this.profileStore = profileStore;
        this.maxConnectionsPerProfile = properties.getConnection().getMaxConnectionsPerProfile();
        this.connections = Caffeine.newBuilder()
                .maximumSize(properties.getConnection().getMaxCachedProfiles())
                .expireAfterAccess(Duration.ofMinutes(properties.getConnection().getExpireAfterAccessMinutes()))
                .removalListener((Long id, ProfileConnection conn, RemovalCause cause) -> {
                    if (conn != null) {
                        // LRU 驱逐 / 空闲超时 / 显式失效 统一在此关闭，杜绝连接泄漏
                        conn.close();
                        log.info("连接被驱逐: profileId={} cause={}", id, cause);
                    }
                })
                .build();
    }

    /**
     * 取（必要时懒建）profile 的连接集。
     * profile 配置不存在 → 40401（存储层判定）。
     */
    public ProfileConnection getConnection(Long profileId) {
        ProfileConnection cached = connections.getIfPresent(profileId);
        if (cached != null) {
            return cached;
        }
        // 先解析 profile（不存在直接 40401，不进缓存）
        RedisProfile profile = profileStore.resolveProfile(profileId);
        return connections.get(profileId, id -> {
            log.info("懒建连接: profileId={} name={} mode={} endpoint={}",
                    id, profile.getName(), profile.getMode(), profile.endpoint());
            ProfileConnection created = new ProfileConnection(
                    profile, clientFactory, timeouts, maxConnectionsPerProfile);
            states.put(id, ProfileState.CONNECTED);
            return created;
        });
    }

    /**
     * 同步命令入口（集群模式下 db 必须为 0，否则 40001）。
     */
    public RedisClusterCommands<String, String> getSyncCommands(Long profileId, int db) {
        return getConnection(profileId).getSyncCommands(db);
    }

    public RedisAdvancedClusterCommands<String, String> getClusterCommands(Long profileId) {
        return getConnection(profileId).getClusterCommands();
    }

    /**
     * 配置 UPDATE 后驱逐旧连接，下次访问懒重建。
     */
    public void refresh(Long profileId) {
        ProfileConnection removed = connections.getIfPresent(profileId);
        if (removed != null) {
            // invalidate 触发 removalListener → close
            connections.invalidate(profileId);
            log.info("配置更新，驱逐旧连接待重建: profileId={}", profileId);
        }
        states.remove(profileId);
    }

    /**
     * 主动关闭（删除配置时先调用）。
     */
    public void close(Long profileId) {
        connections.invalidate(profileId);
        states.remove(profileId);
    }

    @PreDestroy
    public void shutdown() {
        log.info("关闭全部连接（{} 个）", connections.estimatedSize());
        connections.invalidateAll();
        connections.cleanUp();
    }

    /**
     * 心跳：遍历<b>缓存中</b>的连接探活。失败 → close + 移出缓存 + 内存态标 OFFLINE；
     * 成功 → 内存态置 CONNECTED。<b>不写库</b>。
     */
    @Scheduled(fixedRateString = "${redis-admin.connection.heartbeat-interval-ms:60000}")
    public void heartbeat() {
        for (Long profileId : connections.asMap().keySet()) {
            ProfileConnection conn = connections.getIfPresent(profileId);
            if (conn == null) {
                continue;
            }
            RedisProfile profile = conn.getProfile();
            boolean healthy;
            try {
                healthy = conn.ping();
            } catch (Exception e) {
                log.warn("心跳异常 profileId={} name={} mode={}: {}",
                        profileId, profile.getName(), profile.getMode(), e.getMessage());
                healthy = false;
            }
            if (healthy) {
                states.put(profileId, ProfileState.CONNECTED);
            } else {
                log.warn("心跳失败，标记离线并移出缓存: profileId={} name={} mode={} endpoint={}",
                        profileId, profile.getName(), profile.getMode(), profile.endpoint());
                states.put(profileId, ProfileState.OFFLINE);
                connections.invalidate(profileId);
            }
        }
    }

    /**
     * 内存态；无心跳记录时按「尚未连过」返回 OFFLINE。
     */
    public ProfileState getState(Long profileId) {
        return states.getOrDefault(profileId, ProfileState.OFFLINE);
    }

    /**
     * 供 validate 使用：临时建 client 探活，成功即关。不进缓存。
     */
    public ProfileConnection createEphemeral(RedisProfile profile) {
        return new ProfileConnection(profile, clientFactory, timeouts, 1);
    }

    /**
     * 供 validate 使用：解析 profile，不存在抛 40401。
     */
    public RedisProfile requireProfile(Long profileId) {
        return profileStore.resolveProfile(profileId);
    }

    /**
     * 集群模式下校验 db：db&gt;0 直接 40001（带明确 msg，比静默忽略可诊断）。
     */
    public void assertDbAllowed(RedisProfile profile, int db) {
        if (profile.getMode() == DeployMode.CLUSTER && db != 0) {
            throw BizException.of(ErrorCode.VALIDATION_ERROR,
                    "集群模式不支持选库（db 必须为 0），当前请求 db=" + db);
        }
    }
}
