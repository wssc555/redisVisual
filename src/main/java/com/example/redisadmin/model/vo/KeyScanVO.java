package com.example.redisadmin.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * SCAN 分页结果。属于 3 类 {@code @JsonInclude(NON_NULL)} 白名单之一
 * （另两类：ApiResponse / KeyDetailVO）。
 * <p>语义提示：{@code count} 是 Redis 的提示值，{@code keys} 长度不保证等于 {@code count}。
 * {@code nextCursor} 不透明（单机为数字；集群为 {@code "host|port|nodeCursor"}），
 * 客户端只负责原样回传。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KeyScanVO {
    /**
     * 下一次 SCAN 的游标（不透明字符串），耗尽时为 "0"。
     */
    private String nextCursor;
    private List<String> keys;
    /** 游标已耗尽（本次为末页）。 */
    private boolean exhausted;
}
