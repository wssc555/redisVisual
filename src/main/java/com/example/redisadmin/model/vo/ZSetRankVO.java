package com.example.redisadmin.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZSetRankVO {
    /**
     * 排名（0 起）；成员不存在时返回 40402。
     */
    private Long rank;
    private Double score;
}
