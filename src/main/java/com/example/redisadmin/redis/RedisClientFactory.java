package com.example.redisadmin.redis;

import com.example.redisadmin.exception.BizException;
import com.example.redisadmin.exception.ErrorCode;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 三形态建 client 与建 URI。
 * <ul>
 *   <li>STANDALONE：{@code RedisURI.Builder.redis(host, port)} + 认证 + {@code withDatabase}</li>
 *   <li>CLUSTER：种子节点逐一建 URI，<b>每个 URI 都带认证</b>（漏带则带认证的集群直接 50302）</li>
 *   <li>SENTINEL：哨兵 URI 与数据 URI <b>分别携带各自密码</b>（RedisURI 中两者语义独立：
 *       {@code withSentinel(h, p, password)} 属哨兵节点，{@code withPassword} 属数据节点）</li>
 * </ul>
 * <p>不引连接池：lettuce 的 {@code StatefulRedisConnection} 线程安全，
 * 单连接足以支撑可视化工具并发；资源共享点在全局 {@link ClientResources}。
 * lettuce 不提供 {@code connect(int db)}，按 db 隔离通过
 * {@link #buildUri(RedisProfile, int)} 生成带 db 的 URI 后
 * {@code connect(RedisURI)} 实现。</p>
 */
@Component
public class RedisClientFactory {

    private static final Logger log = LoggerFactory.getLogger(RedisClientFactory.class);

    private final ClientResources clientResources;
    private final RedisTimeouts timeouts;

    public RedisClientFactory(RedisTimeouts timeouts) {
        this.timeouts = timeouts;
        // 全局共享 netty 资源，默认单例语义，随应用生命周期
        this.clientResources = DefaultClientResources.builder()
                .ioThreadPoolSize(Runtime.getRuntime().availableProcessors())
                .computationThreadPoolSize(2)
                .build();
    }

    /**
     * 应用关闭时释放共享 netty 资源（所有 client 共用这一份）。
     */
    @PreDestroy
    public void shutdown() {
        clientResources.shutdown().awaitUninterruptibly(2, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * 按部署形态与目标 db 构建数据连接的 URI。
     * 单机/哨兵用于按 db 懒建独立连接（避免共享连接上 select(db) 的并发竞态）；
     * 集群不使用此方法（固定 db0）。
     */
    public RedisURI buildUri(RedisProfile profile, int db) {
        return switch (profile.getMode()) {
            case STANDALONE -> {
                if (isBlank(profile.getHost())) {
                    throw BizException.of(ErrorCode.VALIDATION_ERROR, "单机模式必须配置 host");
                }
                RedisURI.Builder builder = RedisURI.Builder.redis(profile.getHost(), portOrDefault(profile.getPort(), 6379));
                yield withAuth(builder, profile).withDatabase(db).build();
            }
            case SENTINEL -> {
                List<RedisProfile.Node> sentinels = profile.getSentinels();
                if (sentinels.isEmpty()) {
                    throw BizException.of(ErrorCode.VALIDATION_ERROR, "哨兵模式必须至少配置一个哨兵节点");
                }
                if (isBlank(profile.getMasterName())) {
                    throw BizException.of(ErrorCode.VALIDATION_ERROR, "哨兵模式必须配置 masterName");
                }
                RedisProfile.Node first = sentinels.get(0);
                // 哨兵节点密码与数据节点密码相互独立
                RedisURI.Builder builder = isBlank(profile.getSentinelPassword())
                        ? RedisURI.Builder.sentinel(first.getHost(), first.getPort())
                        : RedisURI.Builder.sentinel(first.getHost(), first.getPort(), profile.getSentinelPassword());
                for (int i = 1; i < sentinels.size(); i++) {
                    RedisProfile.Node node = sentinels.get(i);
                    if (isBlank(profile.getSentinelPassword())) {
                        builder.withSentinel(node.getHost(), node.getPort());
                    } else {
                        builder.withSentinel(node.getHost(), node.getPort(), profile.getSentinelPassword());
                    }
                }
                builder.withSentinelMasterId(profile.getMasterName());
                yield withAuth(builder, profile).withDatabase(db).build();
            }
            case CLUSTER -> throw BizException.of(ErrorCode.VALIDATION_ERROR,
                    "集群模式固定 db0，不支持按 db 建连接");
        };
    }

    /**
     * 单机 client。
     */
    public RedisClient createStandaloneClient(RedisProfile profile) {
        RedisURI uri = buildUri(profile, profile.getDatabaseOrDefault());
        log.info("建立单机 client: profileId={} name={} endpoint={} db={}",
                profile.getId(), profile.getName(), profile.endpoint(), uri.getDatabase());
        RedisClient client = RedisClient.create(clientResources, uri);
        client.setOptions(timeouts.clientOptions());
        return client;
    }

    /**
     * 集群 client。每个种子 URI 都带认证。
     * <p>拓扑刷新后新发现的节点沿用种子 URI 的认证信息。</p>
     */
    public RedisClusterClient createClusterClient(RedisProfile profile) {
        List<RedisProfile.Node> nodes = profile.getNodes();
        if (nodes.isEmpty()) {
            throw BizException.of(ErrorCode.VALIDATION_ERROR, "集群模式必须至少配置一个种子节点");
        }
        List<RedisURI> uris = new ArrayList<>(nodes.size());
        for (RedisProfile.Node node : nodes) {
            uris.add(withAuth(RedisURI.Builder.redis(node.getHost(), node.getPort()), profile).build());
        }
        log.info("建立集群 client: profileId={} name={} seeds={}", profile.getId(), profile.getName(), nodes.size());
        RedisClusterClient client = RedisClusterClient.create(clientResources, uris);
        client.setOptions(timeouts.clusterClientOptions());
        return client;
    }

    /**
     * 哨兵 client（哨兵密码与数据密码各自独立）。
     */
    public RedisClient createSentinelClient(RedisProfile profile) {
        RedisURI uri = buildUri(profile, profile.getDatabaseOrDefault());
        log.info("建立哨兵 client: profileId={} name={} master={} sentinels={} db={}",
                profile.getId(), profile.getName(), profile.getMasterName(),
                profile.getSentinels().size(), uri.getDatabase());
        RedisClient client = RedisClient.create(clientResources, uri);
        client.setOptions(timeouts.clientOptions());
        return client;
    }

    /**
     * 施加数据节点认证（ACL username+password，或仅 password）。
     */
    private RedisURI.Builder withAuth(RedisURI.Builder builder, RedisProfile profile) {
        if (!isBlank(profile.getUsername())) {
            builder.withAuthentication(profile.getUsername(),
                    profile.getPassword() == null ? "" : profile.getPassword());
        } else if (!isBlank(profile.getPassword())) {
            builder.withPassword(profile.getPassword());
        }
        return builder;
    }

    private static int portOrDefault(Integer port, int fallback) {
        return port == null || port <= 0 ? fallback : port;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
