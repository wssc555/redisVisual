package com.example.redisadmin.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * String 值。值超过 1MB 时服务端截断预览并置 {@code truncated=true}，
 * 同时回完整 {@code length}（设计 §8 String 截断：全量返回会打爆浏览器）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StringValueVO {
    /**
     * 预览值（可能被截断）。
     */
    private String value;
    /** 完整长度（STRLEN），非预览长度。 */
    private Long length;
    /**
     * 是否因超阈值被截断。
     */
    private boolean truncated;
}
