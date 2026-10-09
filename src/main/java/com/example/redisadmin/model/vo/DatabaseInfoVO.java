package com.example.redisadmin.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个 db 的 key 数量（{@code INFO keyspace} 解析结果；集群固定 db0）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseInfoVO {
    private int db;
    private long keys;
}
