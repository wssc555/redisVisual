package com.example.redisadmin.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 测试连接结果 {@code {reachable, latencyMs, nodeCount, mode, error}}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidateResultVO {

    private boolean reachable;
    private int latencyMs;
    /**
     * 节点数：集群返回分区数，其余为 1。
     */
    private int nodeCount;
    private String mode;
    /**
     * 失败原因（可达时为 null）。
     */
    private String error;
}
