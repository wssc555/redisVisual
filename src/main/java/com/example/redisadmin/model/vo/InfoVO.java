package com.example.redisadmin.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * INFO 分节结构化结果。各分节可能为 null（未请求该分节或该 Redis 版本无此分节）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InfoVO {
    private Map<String, String> server;
    private Map<String, String> clients;
    private Map<String, String> memory;
    private Map<String, String> persistence;
    private Map<String, String> stats;
    private Map<String, String> replication;
    private Map<String, String> cpu;
    private Map<String, String> keyspace;
}
