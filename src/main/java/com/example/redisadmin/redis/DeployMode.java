package com.example.redisadmin.redis;

/**
 * 部署形态。决定 {@link RedisClientFactory} 的建 client 路径。
 */
public enum DeployMode {
    /**
     * 单机
     */
    STANDALONE,
    /**
     * 集群（固定 db0）
     */
    CLUSTER,
    /**
     * 哨兵（哨兵密码与数据密码相互独立）
     */
    SENTINEL
}
