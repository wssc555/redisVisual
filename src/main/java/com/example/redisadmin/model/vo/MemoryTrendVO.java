package com.example.redisadmin.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 内存趋势序列。数据来自内存环形缓冲，<b>不落库、应用重启即清零</b>
 * （明确不承诺持久化）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryTrendVO {
    private List<MemorySampleVO> samples;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemorySampleVO {
        private LocalDateTime timestamp;
        private List<MemoryPointVO> points;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemoryPointVO {
        private Long profileId;
        private String name;
        private Long usedMemoryBytes;
    }
}
