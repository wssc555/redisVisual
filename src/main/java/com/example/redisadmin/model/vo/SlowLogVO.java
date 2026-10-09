package com.example.redisadmin.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlowLogVO {
    private Long id;
    private String timestamp;
    /**
     * 命令耗时（微秒）。
     */
    private Long durationUs;
    private String command;
}
