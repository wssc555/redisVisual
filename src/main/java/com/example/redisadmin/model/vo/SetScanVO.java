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
public class SetScanVO {
    private Long nextCursor;
    private List<String> members;
    /**
     * SCARD 总数。
     */
    private Long size;
}
