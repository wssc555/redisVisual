package com.example.redisadmin.redis;

import com.example.redisadmin.config.AppProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 超时三件套的单一装配点。
 * <ul>
 *   <li>connect timeout 5s —— {@link SocketOptions}，控制建连阶段</li>
 *   <li>command timeout 3s —— {@link TimeoutOptions}，sync 命令级超时，
 *       超时抛 {@code RedisCommandTimeoutException} → 50302</li>
 *   <li>disconnect timeout —— 走 {@code shutdown(drainTimeout, quietPeriod)}</li>
 * </ul>
 * 集群额外叠加拓扑刷新：周期 60s + adaptive（节点变更事件触发）。
 */
@Component
public class RedisTimeouts {

    private final Duration connectTimeout;
    private final Duration commandTimeout;
    private final Duration shutdownQuietPeriod;
    private final Duration shutdownDrainTimeout;
    private final Duration topologyRefreshPeriod;

    public RedisTimeouts(AppProperties properties) {
        AppProperties.Connection connection = properties.getConnection();
        this.connectTimeout = Duration.ofMillis(connection.getConnectTimeoutMs());
        this.commandTimeout = Duration.ofMillis(connection.getCommandTimeoutMs());
        this.shutdownQuietPeriod = Duration.ofMillis(200);
        this.shutdownDrainTimeout = Duration.ofMillis(2000);
        this.topologyRefreshPeriod = Duration.ofSeconds(connection.getTopologyRefreshPeriodSeconds());
    }

    /**
     * 单机 / 哨兵 client 的连接选项。
     */
    public ClientOptions clientOptions() {
        return ClientOptions.builder()
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(connectTimeout)
                        .keepAlive(true)
                        .build())
                .timeoutOptions(TimeoutOptions.enabled(commandTimeout))
                .autoReconnect(true)
                .build();
    }

    /**
     * 集群 client 的连接选项，叠加拓扑刷新策略。
     */
    public ClusterClientOptions clusterClientOptions() {
        return ClusterClientOptions.builder()
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(connectTimeout)
                        .keepAlive(true)
                        .build())
                .timeoutOptions(TimeoutOptions.enabled(commandTimeout))
                .autoReconnect(true)
                .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
                        .enablePeriodicRefresh(topologyRefreshPeriod)
                        .enableAllAdaptiveRefreshTriggers()
                        .build())
                .build();
    }

    public Duration getShutdownQuietPeriod() {
        return shutdownQuietPeriod;
    }

    public Duration getShutdownDrainTimeout() {
        return shutdownDrainTimeout;
    }
}
