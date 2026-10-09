package com.example.redisadmin.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 实时检索的命中条目（key / value 关键词命中当前存活键空间）。
 * <p>字段口径对齐 {@link KeyDetailVO}（size 为元素数、String 为 STRLEN 字节数；
 * ttlSeconds -1=永久 / -2=不存在），另加 {@code matchedPreview} 标示命中片段。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeySearchHitVO {

    private String key;
    private String type;
    /**
     * 元素数；String 为 STRLEN 字节数。与 KeyDetailVO.size 同口径。
     */
    private Long size;
    private Long ttlSeconds;
    private String ttlFormat;
    /**
     * 命中片段（截断预览）；key 维度命中时为 null。
     */
    private String matchedPreview;
    /**
     * matchedPreview 是否因长度上限被截断。
     */
    private boolean truncated;
}