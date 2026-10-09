package com.example.redisadmin.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 跨实例聚合概览。总量字段只累加在线实例；离线实例仍出现在 {@code instances} 中，
 * 便于前端标灰显示（设计 §15.11：1 在线 + 1 离线时 overview 仍正常聚合）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardOverviewVO {
    private int totalProfiles;
    private int onlineProfiles;
    private int offlineProfiles;
    private long memoryUsageBytes;
    private String memoryUsageHuman;
    private long peakMemoryBytes;
    private long clients;
    private long opsPerSec;
    private long totalKeys;
    private List<InstanceMetricsVO> instances;
}
