package com.example.redisadmin.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单实例概览卡片（{@code /server/overview}）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerOverviewVO {
    /**
     * 人类可读，如 12.34M。
     */
    private String usedMemory;
    private Long usedMemoryBytes;
    private String peakMemory;
    private Integer connectedClients;
    private Long totalCommands;
    private Long opsPerSec;
    private Long keyspaceHits;
    private Long keyspaceMisses;
    private Double hitRate;
    private Long uptimeDays;
    /**
     * Redis 版本，前端可据此提示命令兼容性。
     */
    private String redisVersion;
}
