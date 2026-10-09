package com.example.redisadmin.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Key 详情。属于 3 类 {@code @JsonInclude(NON_NULL)} 白名单之一
 * （另两类：ApiResponse / KeyScanVO）—— memoryUsageBytes 在低版本 Redis 上可能为 null。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KeyDetailVO {
    private String key;
    private String type;
    /**
     * 元素数；String 为 STRLEN 字节数。
     */
    private Long size;
    /** -1 = 永久，-2 = 不存在。 */
    private Long ttlSeconds;
    private String ttlFormat;
    private boolean exists;
    /**
     * {@code OBJECT ENCODING} 结果，如 embstr / listpack / intset。
     */
    private String encoding;
    /**
     * {@code MEMORY USAGE}（SAMPLES 5），不可用时为 null。
     */
    private Long memoryUsageBytes;
}
