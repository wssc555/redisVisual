package com.example.redisadmin.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Hash 字段。字段值超 1MB 时截断预览（HSCAN 取回后截断，HGET 无对应 stride 命令）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HashFieldVO {
    private String field;
    /**
     * 预览值（可能被截断）。
     */
    private String value;
    private boolean truncated;
}
