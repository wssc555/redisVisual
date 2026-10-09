package com.example.redisadmin.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 负载趋势（QPS / 客户端数）序列。同内存趋势：不落库、重启清零。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoadTrendVO {
    private List<LoadSampleVO> samples;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoadSampleVO {
        private LocalDateTime timestamp;
        private List<LoadPointVO> points;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoadPointVO {
        private Long profileId;
        private String name;
        private Long opsPerSec;
        private Integer clients;
    }
}
