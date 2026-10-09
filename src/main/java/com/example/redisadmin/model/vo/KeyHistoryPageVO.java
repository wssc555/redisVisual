package com.example.redisadmin.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 操作日志分页查询结果。
 * <p>注意 {@code valuePreview} 的 LIKE 是<b>全表扫描</b>（{@code %kw%} 无法走索引），
 * 故 pageSize 由 {@code history.max-page-size} 硬钳制；日志量大时优先用 key / 时间范围收窄。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyHistoryPageVO {

    /**
     * 命中总条数（与分页无关的 COUNT(1) 真值）。
     */
    private Long total;
    private Integer page;
    private Integer pageSize;
    private List<KeyHistoryVO> items;
}