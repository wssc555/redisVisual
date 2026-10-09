package com.example.redisadmin.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 测试连接请求。带 {@code id} 且密码为 {@code "******"}/缺省时，
 * 从库取密文解密补全再测试（对齐 kafkaVisual5 validate 的省略补全语义）。
 */
@Data
public class ProfileValidateDTO {

    /**
     * 可选：带上则支持「省略密码」补全。
     */
    private Long id;

    @NotNull(message = "部署模式不能为空")
    private com.example.redisadmin.redis.DeployMode mode;

    private String host;
    private Integer port;
    private java.util.List<ProfileDTO.NodeDTO> nodes;
    private java.util.List<ProfileDTO.NodeDTO> sentinels;
    private String masterName;
    private Integer database = 0;
    private String username;
    private String password;
    private String sentinelPassword;
}
