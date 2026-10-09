package com.example.redisadmin.model.vo;

import com.example.redisadmin.redis.DeployMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 连接配置响应 VO。<b>永不包含密码明文或可逆向值</b>，
 * 只回 {@code credentialPresence}（NONE / PRESENT）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileVO {

    private Long id;
    private String name;
    private DeployMode mode;

    private String host;
    private Integer port;

    /**
     * CLUSTER 种子节点。
     */
    private List<NodeVO> nodes;

    /**
     * SENTINEL 哨兵节点。
     */
    private List<NodeVO> sentinels;
    private String masterName;

    private Integer database;
    private String username;

    /**
     * 凭据存在性：仅表达「有没有配」，不回显值。
     */
    private CredentialPresence credentialPresence;
    /**
     * 哨兵凭据存在性。
     */
    private CredentialPresence sentinelCredentialPresence;

    /**
     * 运行时状态（内存态，非持久化）。
     */
    private String profileState;

    /**
     * 节点地址（脱敏，host:port）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeVO {
        private String host;
        private Integer port;
    }
}
