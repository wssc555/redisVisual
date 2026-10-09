package com.example.redisadmin.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单实例指标（仪表盘 overview 的实例级条目）。
 * <p>离线实例字段全部归零并附 {@code latencyMs=null}，
 * <b>不拖垮整体响应</b>（单实例超时由 lettuce 命令级 3s 封顶）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstanceMetricsVO {
    private Long profileId;
    private String name;
    private String mode;
    /**
     * CONNECTED / OFFLINE（内存态）。
     */
    private String profileState;
    private Integer nodeCount;
    private Integer masterCount;
    private Integer slaveCount;
    private String clusterState;
    private Long memoryUsageBytes;
    private String memoryUsageHuman;
    private Long peakMemoryBytes;
    private Long maxMemoryBytes;
    private Double memoryUsagePercent;
    private Integer clients;
    private Long opsPerSec;
    /**
     * CPU 占用率（%，{@code used_cpu_sys + used_cpu_user} 差分）。
     * <p>数据来源：{@code INFO cpu} 累计秒数由仪表盘采样任务（默认 5s）差分计算，
     * 单机为该实例值，集群为各 master 节点均值；启动后需 2 个采样点（约 10s）才有值，
     * 之前为 null（区别于离线的 0.0）。</p>
     */
    private Double cpuUsagePercent;
    private Long totalKeys;
    private Double hitRate;
    private Long latencyMs;
}
