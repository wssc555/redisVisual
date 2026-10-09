package com.example.redisadmin.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 节点列表 JSON 编解码。读取失败一律降级为空列表
 * （脏数据不应导致整个 profile 列表不可用；建连时会因节点缺失被显式拒绝）。
 */
public final class JsonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<ProfileRow.NodeRow>> NODE_LIST = new TypeReference<>() {
    };

    private JsonCodec() {
    }

    public static String writeNodeList(List<ProfileRow.NodeRow> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(nodes);
        } catch (Exception e) {
            throw new IllegalArgumentException("节点列表序列化失败", e);
        }
    }

    public static List<ProfileRow.NodeRow> readNodeList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<ProfileRow.NodeRow> nodes = MAPPER.readValue(json, NODE_LIST);
            return nodes == null ? List.of() : nodes;
        } catch (Exception e) {
            return List.of();
        }
    }
}
