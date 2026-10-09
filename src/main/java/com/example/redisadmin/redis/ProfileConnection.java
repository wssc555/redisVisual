package com.example.redisadmin.redis;

import com.example.redisadmin.exception.BizException;
import com.example.redisadmin.exception.ErrorCode;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单个 profile 的连接集。
 * <ul>
 *   <li>单机 / 哨兵：按 {@code db} 维度懒建 {@link StatefulRedisConnection}
 *       —— 每 db 独立建连，绝不在共享连接上 select(db)（并发请求会互相踩库）</li>
 *   <li>集群：单条 {@link StatefulRedisClusterConnection}，固定 db0</li>
 * </ul>
 * 生命周期由 {@link ProfileConnectionManager} 通过 Caffeine removalListener 驱动 close。
 */
public class ProfileConnection {

    private static final Logger log = LoggerFactory.getLogger(ProfileConnection.class);

    private final RedisProfile profile;
    private final RedisClientFactory clientFactory;
    private final RedisTimeouts timeouts;
    private final int maxConnectionsPerProfile;

    private final RedisClient standaloneClient;
    private final RedisClusterClient clusterClient;
    private final Map<Integer, StatefulRedisConnection<String, String>> connsByDb = new ConcurrentHashMap<>();

    private volatile StatefulRedisClusterConnection<String, String> clusterConn;

    public ProfileConnection(RedisProfile profile, RedisClientFactory clientFactory, RedisTimeouts timeouts,
                             int maxConnectionsPerProfile) {
        this.profile = profile;
        this.clientFactory = clientFactory;
        this.timeouts = timeouts;
        this.maxConnectionsPerProfile = maxConnectionsPerProfile;

        RedisClient standalone = null;
        RedisClusterClient cluster = null;
        try {
            switch (profile.getMode()) {
                case STANDALONE -> standalone = clientFactory.createStandaloneClient(profile);
                case SENTINEL -> standalone = clientFactory.createSentinelClient(profile);
                case CLUSTER -> cluster = clientFactory.createClusterClient(profile);
                default -> throw BizException.of(ErrorCode.VALIDATION_ERROR,
                        "不支持的部署模式: " + profile.getMode());
            }
        } catch (BizException e) {
            shutdownQuietly(standalone, cluster);
            throw e;
        } catch (Exception e) {
            shutdownQuietly(standalone, cluster);
            throw BizException.of(ErrorCode.REDIS_UNAVAILABLE, describe(e));
        }
        this.standaloneClient = standalone;
        this.clusterClient = cluster;
    }

    /**
     * 构造失败时回收已创建的 client，避免半初始化泄漏。
     */
    private static void shutdownQuietly(RedisClient standalone, RedisClusterClient cluster) {
        try {
            if (standalone != null) {
                standalone.shutdown();
            }
            if (cluster != null) {
                cluster.shutdown();
            }
        } catch (Exception ignored) {
            // 构造期清理失败无需上抛，原始异常更有诊断价值
        }
    }

    public RedisProfile getProfile() {
        return profile;
    }

    /**
     * 取指定 db 的同步命令入口。
     * <p>集群模式忽略 db 是错的：传 db&gt;0 直接 40001 并给出明确 msg，比静默忽略可诊断。</p>
     * <p>返回类型用 {@link RedisClusterCommands}：lettuce 集群 sync() 是只实现
     * {@code RedisAdvancedClusterCommands} 的动态代理，<b>不是</b> {@code RedisCommands}，
     * 强转 {@code (RedisCommands)(Object)} 会在首个集群命令上抛 ClassCastException；
     * 两者共同的父接口是 RedisClusterCommands（含全部数据命令），单机侧 RedisCommands
     * 是它的子接口，可安全加宽。</p>
     */
    public RedisClusterCommands<String, String> getSyncCommands(int db) {
        if (profile.getMode() == DeployMode.CLUSTER) {
            if (db != 0) {
                throw BizException.of(ErrorCode.VALIDATION_ERROR,
                        "集群模式不支持选库（db 必须为 0），当前请求 db=" + db);
            }
            return getClusterCommands();
        }
        return connectionFor(db).sync();
    }

    public RedisAdvancedClusterCommands<String, String> getClusterCommands() {
        if (profile.getMode() != DeployMode.CLUSTER) {
            throw BizException.of(ErrorCode.VALIDATION_ERROR,
                    "仅集群模式支持该操作，当前模式: " + profile.getMode());
        }
        StatefulRedisClusterConnection<String, String> conn = clusterConnection();
        return conn.sync();
    }

    public StatefulRedisClusterConnection<String, String> getClusterConnection() {
        if (profile.getMode() != DeployMode.CLUSTER) {
            throw BizException.of(ErrorCode.VALIDATION_ERROR,
                    "仅集群模式支持该操作，当前模式: " + profile.getMode());
        }
        return clusterConnection();
    }

    public RedisClusterClient getClusterClient() {
        return clusterClient;
    }

    /**
     * 集群当前在线的 master 节点列表（按当前拓扑视图，供逐节点 SCAN 使用）。
     * 非集群模式返回空列表。
     */
    public List<RedisClusterNode> clusterMasters() {
        if (profile.getMode() != DeployMode.CLUSTER || clusterClient == null) {
            return List.of();
        }
        List<RedisClusterNode> masters = new ArrayList<>();
        for (RedisClusterNode node : clusterClient.getPartitions()) {
            if (node.isConnected() && node.getRole().isMaster()) {
                masters.add(node);
            }
        }
        return masters;
    }

    /**
     * 取集群指定节点的独立连接（节点级命令，如逐节点 SCAN）。
     */
    public StatefulRedisConnection<String, String> nodeConnection(String host, int port) {
        return clusterConnection().getConnection(host, port);
    }

    private StatefulRedisClusterConnection<String, String> clusterConnection() {
        StatefulRedisClusterConnection<String, String> conn = clusterConn;
        if (conn == null || !conn.isOpen()) {
            synchronized (this) {
                if (clusterConn == null || !clusterConn.isOpen()) {
                    if (clusterConn != null) {
                        clusterConn.close();
                    }
                    try {
                        clusterConn = clusterClient.connect();
                    } catch (Exception e) {
                        throw BizException.of(ErrorCode.REDIS_UNAVAILABLE, describe(e));
                    }
                }
                conn = clusterConn;
            }
        }
        return conn;
    }

    private StatefulRedisConnection<String, String> connectionFor(int db) {
        if (db < 0) {
            throw BizException.of(ErrorCode.VALIDATION_ERROR, "db 不能为负数: " + db);
        }
        StatefulRedisConnection<String, String> conn = connsByDb.get(db);
        if (conn != null && conn.isOpen()) {
            return conn;
        }
        synchronized (this) {
            conn = connsByDb.get(db);
            if (conn != null && conn.isOpen()) {
                return conn;
            }
            if (conn != null) {
                // 僵尸连接，先关再重建
                conn.closeAsync().join();
                connsByDb.remove(db);
            }
            // 连接数上限（每 profile 默认 16 db）—— 防连接泄漏
            if (connsByDb.size() >= maxConnectionsPerProfile && !connsByDb.containsKey(db)) {
                throw BizException.of(ErrorCode.VALIDATION_ERROR,
                        "单实例并发使用的 db 数已达上限 " + maxConnectionsPerProfile
                                + "，支持的 db 范围 0.." + (maxConnectionsPerProfile - 1));
            }
            try {
                // 每 db 一条连接：SELECT 随 URI 在建连时下发，不在共享连接上执行
                StatefulRedisConnection<String, String> created =
                        standaloneClient.connect(clientFactory.buildUri(profile, db));
                connsByDb.put(db, created);
                return created;
            } catch (Exception e) {
                throw BizException.of(ErrorCode.REDIS_UNAVAILABLE, describe(e));
            }
        }
    }

    /**
     * 心跳探活：单机/哨兵 PING，集群另验 {@code cluster_state:ok}。
     */
    public boolean ping() {
        try {
            if (profile.getMode() == DeployMode.CLUSTER) {
                String info = getClusterCommands().clusterInfo();
                return info != null && info.contains("cluster_state:ok");
            }
            return "PONG".equalsIgnoreCase(connectionFor(profile.getDatabaseOrDefault()).sync().ping());
        } catch (Exception e) {
            log.debug("心跳失败 profileId={}: {}", profile.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * 全部连接 + client 关闭。幂等。
     */
    public synchronized void close() {
        connsByDb.values().forEach(conn -> {
            try {
                conn.close();
            } catch (Exception ignored) {
                // 关闭异常无需上抛：正在销毁连接
            }
        });
        connsByDb.clear();

        if (clusterConn != null) {
            try {
                clusterConn.close();
            } catch (Exception ignored) {
                // 同上
            }
            clusterConn = null;
        }
        if (standaloneClient != null) {
            standaloneClient.shutdown(timeouts.getShutdownQuietPeriod(), timeouts.getShutdownDrainTimeout());
        }
        if (clusterClient != null) {
            clusterClient.shutdown(timeouts.getShutdownQuietPeriod(), timeouts.getShutdownDrainTimeout());
        }
        log.info("已关闭连接: profileId={} name={} mode={}", profile.getId(), profile.getName(), profile.getMode());
    }

    private static String describe(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }
}
