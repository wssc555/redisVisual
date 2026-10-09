package com.example.redisadmin.model.dto;

import com.example.redisadmin.redis.DeployMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 连接配置请求 DTO（创建 / 更新 / validate 共用）。
 * <p>密码三态由前端表达：{@code null} / {@code "******"} 保持现值，
 * {@code ""} 清空，其他值更新（见 {@code ProfileStore}）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProfileDTO {

    @NotBlank(message = "连接名称不能为空")
    @Size(max = 128, message = "连接名称长度不能超过 128")
    private String name;

    @NotNull(message = "部署模式不能为空")
    private DeployMode mode;

    private String host;
    private Integer port;

    /**
     * CLUSTER 种子节点，至少 1 个。
     */
    @Valid
    private List<NodeDTO> nodes;

    /** SENTINEL 哨兵节点，至少 1 个。 */
    @Valid
    private List<NodeDTO> sentinels;

    private String masterName;

    private Integer database = 0;

    private String username;

    /** 见类注释三态语义。永不回显明文。 */
    private String password;

    /**
     * 哨兵节点自身密码，与数据节点密码独立。
     */
    private String sentinelPassword;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeDTO {
        @NotBlank(message = "节点主机地址不能为空")
        private String host;

        @NotNull(message = "节点端口不能为空")
        private Integer port;
    }
}
