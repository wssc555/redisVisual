package com.example.redisadmin.storage;

import com.example.redisadmin.redis.DeployMode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code redis_profile} 表的行映射。仅存储层内部使用；
 * 对外 API 一律经 {@code ProfileVO} 输出（不含凭据明文）。
 * <p>{@code password} / {@code sentinelPassword} 存 AES-GCM 密文。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProfileRow {

    private Long id;
    private String name;
    private DeployMode mode;
    private String host;
    private Integer port;
    /**
     * 集群种子节点 JSON：{@code [{"host":"...","port":6379}]}
     */
    private String nodes;
    /**
     * 哨兵节点 JSON，同上结构
     */
    private String sentinels;
    private String masterName;
    private Integer database;
    private String username;
    private String password;
    private String sentinelPassword;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 节点 JSON 的元素（落库为 JSON 数组文本，读出为对象列表）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeRow {
        private String host;
        private Integer port;
    }

    public List<NodeRow> nodeList() {
        return JsonCodec.readNodeList(nodes);
    }

    public List<NodeRow> sentinelList() {
        return JsonCodec.readNodeList(sentinels);
    }
}
