package com.example.redisadmin.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZSetRangeVO {
    /**
     * ZCARD 真值总数（非当页条数）。
     */
    private Long total;
    private List<ZSetMemberVO> items;
}
