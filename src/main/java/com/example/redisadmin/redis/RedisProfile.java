package com.example.redisadmin.redis;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.Collections;
import java.util.List;

/**
 * 不可变连接定义，从库行解出（含已解密的明文凭据，仅在连接域内部流转，绝不外发）。
 * <p>运行时状态（在线/离线）不在此对象内——那属于内存态
 * （见 {@link ProfileState}），不落库。</p>
 */
@Getter
@Builder
@ToString(of = {"id", "name", "mode", "host", "port", "masterName", "database"})
public class RedisProfile {

    private final Long id;
    private final String name;
    private final DeployMode mode;

    // STANDALONE
    private final String host;
    private final Integer port;

    // CLUSTER
    private final List<Node> nodes;

    // SENTINEL
    private final List<Node> sentinels;
    private final String masterName;

    private final Integer database;

    // 认证（明文，仅内存）
    private final String username;
    private final String password;
    /**
     * 哨兵节点自身密码，与数据节点密码独立。
     */
    private final String sentinelPassword;

    public List<Node> getNodes() {
        return nodes == null ? Collections.emptyList() : nodes;
    }

    public List<Node> getSentinels() {
        return sentinels == null ? Collections.emptyList() : sentinels;
    }

    public int getDatabaseOrDefault() {
        return database == null ? 0 : database;
    }

    /**
     * host:port 日志表示，绝不含认证段。
     */
    public String endpoint() {
        return mode == DeployMode.CLUSTER
                ? "cluster" + getNodes()
                : host + ":" + (port == null ? 6379 : port);
    }

    @Getter
    @Builder
    @ToString
    public static class Node {
        private final String host;
        private final int port;
    }
}
