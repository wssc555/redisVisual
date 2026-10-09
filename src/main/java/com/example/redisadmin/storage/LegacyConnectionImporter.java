package com.example.redisadmin.storage;

import com.example.redisadmin.redis.DeployMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 存量 {@code connections.json} 一次性导入工具（对齐 §5.4）。
 * <p>用法：{@code java -jar app.jar --import-connections=path/to/connections.json}。
 * 未指定该参数时不做任何事。存量文件里是明文密码，导入后密码以密文落库，
 * 并打印告警提醒手动删除源文件。</p>
 * <p>幂等：按 name 判重，已存在的名称跳过。</p>
 */
@Component
public class LegacyConnectionImporter {

    private static final Logger log = LoggerFactory.getLogger(LegacyConnectionImporter.class);
    private static final String OPTION = "import-connections";

    private final ProfileStore profileStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LegacyConnectionImporter(ProfileStore profileStore) {
        this.profileStore = profileStore;
    }

    public void importIfRequested(ApplicationArguments args) {
        if (args == null || !args.containsOption(OPTION)) {
            return;
        }
        String file = args.getOptionValues(OPTION).stream().findFirst().orElse(null);
        if (file == null || file.isBlank()) {
            log.warn("指定了 --{} 但未提供路径，跳过导入", OPTION);
            return;
        }
        importFrom(Paths.get(file));
    }

    void importFrom(Path source) {
        if (!Files.exists(source)) {
            log.warn("导入失败：文件不存在 {}", source);
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(source));
            JsonNode profiles = root.has("profiles") ? root.get("profiles") : root;
            if (!profiles.isArray()) {
                log.warn("导入失败：未找到 profiles 数组 {}", source);
                return;
            }

            int inserted = 0;
            int skipped = 0;
            List<String> failures = new ArrayList<>();

            for (JsonNode node : profiles) {
                String name = node.path("name").asText(null);
                try {
                    if (name == null || name.isBlank()) {
                        throw new IllegalArgumentException("缺少 name");
                    }
                    if (profileStore.existsByName(name, null)) {
                        skipped++;
                        continue;
                    }
                    profileStore.insert(toRow(node));
                    inserted++;
                } catch (Exception e) {
                    // 单条失败不阻断整批：跳过并汇总
                    failures.add(name + "(" + e.getMessage() + ")");
                }
            }

            log.info("存量 connections.json 导入完成: 插入 {} 条，跳过（同名已存在）{} 条", inserted, skipped);
            if (!failures.isEmpty()) {
                log.warn("以下条目导入失败已跳过: {}", failures);
            }
            log.warn("存量文件内含明文密码，已加密落库；请手动删除源文件: {}", source.toAbsolutePath());
        } catch (Exception e) {
            log.error("导入失败: {}", source, e);
        }
    }

    private ProfileRow toRow(JsonNode node) {
        ProfileRow row = new ProfileRow();
        row.setName(node.path("name").asText());
        row.setMode(DeployMode.valueOf(node.path("mode").asText("STANDALONE")));
        row.setHost(node.path("host").asText(null));
        row.setPort(node.hasNonNull("port") ? node.get("port").asInt() : 6379);
        row.setNodes(JsonCodec.writeNodeList(readNodes(node.get("nodes"))));
        row.setSentinels(JsonCodec.writeNodeList(readNodes(node.get("sentinels"))));
        row.setMasterName(node.path("masterName").asText(null));
        int database = node.path("database").asInt(0);
        // 集群强制 db0
        row.setDatabase(row.getMode() == DeployMode.CLUSTER ? 0 : database);
        row.setUsername(node.path("username").asText(null));
        // 存量为明文，落库时由 ProfileStore 走三态加密
        row.setPassword(node.path("password").asText(null));
        return row;
    }

    private List<ProfileRow.NodeRow> readNodes(JsonNode array) {
        List<ProfileRow.NodeRow> nodes = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return nodes;
        }
        for (JsonNode item : array) {
            nodes.add(new ProfileRow.NodeRow(
                    item.path("host").asText(null),
                    item.hasNonNull("port") ? item.get("port").asInt() : null));
        }
        return nodes;
    }
}
