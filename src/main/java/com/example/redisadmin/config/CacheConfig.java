package com.example.redisadmin.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Caffeine 缓存 Bean（对齐 kafkaVisual5「三常量」缓存思想，key 常量集中管理）。
 * <p>仅仪表盘需要短 TTL 缓存：避免多前端高频轮询打穿 Redis。</p>
 */
@Configuration
public class CacheConfig {

    /**
     * 仪表盘 overview 缓存 key。
     */
    public static final String DASHBOARD_OVERVIEW = "dashboard:overview";

    /**
     * 集群拓扑缓存 key 前缀（按 profileId 拼后缀）。
     */
    public static final String DASHBOARD_TOPOLOGY_PREFIX = "dashboard:topology:";

    @Bean
    public Cache<String, Object> dashboardCache(AppProperties properties) {
        return Caffeine.newBuilder()
                .maximumSize(64)
                .expireAfterWrite(Duration.ofSeconds(properties.getDashboard().getCacheTtlSeconds()))
                .build();
    }
}
