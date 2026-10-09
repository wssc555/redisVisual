package com.example.redisadmin.service;

import com.example.redisadmin.exception.BizException;
import com.example.redisadmin.exception.ErrorCode;
import com.example.redisadmin.model.vo.ClientInfoVO;
import com.example.redisadmin.model.vo.DatabaseInfoVO;
import com.example.redisadmin.model.vo.InfoVO;
import com.example.redisadmin.model.vo.ServerOverviewVO;
import com.example.redisadmin.model.vo.SlowLogVO;
import com.example.redisadmin.redis.DeployMode;
import com.example.redisadmin.redis.ProfileConnection;
import com.example.redisadmin.redis.ProfileConnectionManager;
import com.example.redisadmin.redis.RedisProfile;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 服务器监控（<b>只读</b>）：INFO 分节解析、CLIENT LIST、SLOWLOG、DBSIZE。
 * <p>安全边界：本服务<b>不提供</b> {@code CONFIG SET} / {@code FLUSHDB} /
 * {@code FLUSHALL} / {@code KEYS} / {@code SHUTDOWN}
 * —— 写入与清空风险必须走 Redis 自有运维通道（见设计 §8）。</p>
 */
@Service
public class ServerService {

    private static final Logger log = LoggerFactory.getLogger(ServerService.class);

    private final ProfileConnectionManager connectionManager;

    public ServerService(ProfileConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    /**
     * INFO 分节结构化。集群可用 {@code node=host:port} 指定节点，
     * 缺省取首个 master。
     */
    public InfoVO getInfo(Long profileId, String section, String node) {
        RedisProfile profile = connectionManager.requireProfile(profileId);
        return parseInfo(rawInfo(profileId, profile, section, node));
    }

    private String rawInfo(Long profileId, RedisProfile profile, String section, String node) {
        if (profile.getMode() != DeployMode.CLUSTER) {
            RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profileId, 0);
            return commands.info(section);
        }
        ProfileConnection connection = connectionManager.getConnection(profileId);
        if (node != null && !node.isBlank()) {
            String[] parts = node.split(":");
            if (parts.length != 2) {
                throw BizException.of(ErrorCode.VALIDATION_ERROR, "node 格式应为 host:port，当前: " + node);
            }
            StatefulRedisConnection<String, String> conn = connection.getClusterConnection()
                    .getConnection(parts[0], Integer.parseInt(parts[1]));
            return conn.sync().info(section);
        }
        // 缺省取首个 master；无 master 时退回聚合入口
        for (RedisClusterNode rcn : connection.getClusterClient().getPartitions()) {
            if (rcn.getRole().isMaster() && rcn.isConnected()) {
                StatefulRedisConnection<String, String> conn = connection.getClusterConnection()
                        .getConnection(rcn.getUri().getHost(), rcn.getUri().getPort());
                return conn.sync().info(section);
            }
        }
        return connection.getClusterCommands().info(section);
    }

    public List<ClientInfoVO> getClients(Long profileId) {
        RedisProfile profile = connectionManager.requireProfile(profileId);
        String raw = profile.getMode() == DeployMode.CLUSTER
                ? connectionManager.getClusterCommands(profileId).clientList()
                : connectionManager.getSyncCommands(profileId, 0).clientList();
        return parseClientList(raw);
    }

    public List<SlowLogVO> getSlowLog(Long profileId, int count) {
        RedisProfile profile = connectionManager.requireProfile(profileId);
        List<Object> entries = profile.getMode() == DeployMode.CLUSTER
                ? connectionManager.getClusterCommands(profileId).slowlogGet(count)
                : connectionManager.getSyncCommands(profileId, 0).slowlogGet(count);
        return parseSlowLog(entries);
    }

    /**
     * 集群返回各 master DBSIZE 之和。
     */
    public long getDbSize(Long profileId, int db) {
        RedisProfile profile = connectionManager.requireProfile(profileId);
        if (profile.getMode() != DeployMode.CLUSTER) {
            return connectionManager.getSyncCommands(profileId, db).dbsize();
        }
        ProfileConnection connection = connectionManager.getConnection(profileId);
        long total = 0;
        for (RedisClusterNode node : connection.getClusterClient().getPartitions()) {
            if (!node.getRole().isMaster() || !node.isConnected()) {
                continue;
            }
            try {
                total += connection.getClusterConnection()
                        .getConnection(node.getUri().getHost(), node.getUri().getPort())
                        .sync().dbsize();
            } catch (Exception e) {
                // 单节点取不到不应让整体失败
                log.warn("取节点 dbsize 失败: {} - {}", node.getUri(), e.getMessage());
            }
        }
        return total;
    }

    /**
     * 单实例概览卡片（memory + stats 聚合）。
     */
    public ServerOverviewVO getOverview(Long profileId, int db) {
        InfoVO info = getInfo(profileId, "all", null);
        Map<String, String> memory = orEmpty(info.getMemory());
        Map<String, String> stats = orEmpty(info.getStats());
        Map<String, String> clients = orEmpty(info.getClients());

        long hits = parseLong(stats.get("keyspace_hits"));
        long misses = parseLong(stats.get("keyspace_misses"));
        double hitRate = hits + misses > 0 ? (double) hits / (hits + misses) : 0;

        return ServerOverviewVO.builder()
                .usedMemory(memory.getOrDefault("used_memory_human", "0B"))
                .usedMemoryBytes(parseLong(memory.get("used_memory")))
                .peakMemory(memory.getOrDefault("used_memory_peak_human", "0B"))
                .connectedClients(parseInt(clients.get("connected_clients")))
                .totalCommands(parseLong(stats.get("total_commands_processed")))
                .opsPerSec(parseLong(stats.get("instantaneous_ops_per_sec")))
                .keyspaceHits(hits)
                .keyspaceMisses(misses)
                .hitRate(Math.round(hitRate * 1000.0) / 1000.0)
                .uptimeDays(parseLong(orEmpty(info.getServer()).get("uptime_in_days")))
                .redisVersion(orEmpty(info.getServer()).get("redis_version"))
                .build();
    }

    /**
     * 各 db 的 key 数（{@code INFO keyspace} 解析）；集群固定 {@code [{db:0, keys:总Key}]}。
     */
    public List<DatabaseInfoVO> getDatabases(Long profileId) {
        RedisProfile profile = connectionManager.requireProfile(profileId);
        List<DatabaseInfoVO> result = new ArrayList<>();

        if (profile.getMode() == DeployMode.CLUSTER) {
            result.add(DatabaseInfoVO.builder().db(0).keys(getDbSize(profileId, 0)).build());
            return result;
        }

        InfoVO info = getInfo(profileId, "keyspace", null);
        for (Map.Entry<String, String> entry : orEmpty(info.getKeyspace()).entrySet()) {
            String dbName = entry.getKey();
            if (!dbName.startsWith("db")) {
                continue;
            }
            try {
                int dbIndex = Integer.parseInt(dbName.substring(2));
                long keys = parseLong(parseKeyValue(entry.getValue()).get("keys"));
                result.add(DatabaseInfoVO.builder().db(dbIndex).keys(keys).build());
            } catch (NumberFormatException e) {
                log.warn("无法解析 keyspace 条目: {}", dbName);
            }
        }
        result.sort(Comparator.comparingInt(DatabaseInfoVO::getDb));
        return result;
    }

    public static InfoVO parseInfo(String infoRaw) {
        Map<String, Map<String, String>> sections = parseInfoSections(infoRaw);
        InfoVO vo = new InfoVO();
        vo.setServer(sections.get("server"));
        vo.setClients(sections.get("clients"));
        vo.setMemory(sections.get("memory"));
        vo.setPersistence(sections.get("persistence"));
        vo.setStats(sections.get("stats"));
        vo.setReplication(sections.get("replication"));
        vo.setCpu(sections.get("cpu"));
        vo.setKeyspace(sections.get("keyspace"));
        return vo;
    }

    /**
     * INFO 原文 → 分节 map（保序）。
     */
    public static Map<String, Map<String, String>> parseInfoSections(String info) {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        if (info == null || info.isBlank()) {
            return sections;
        }
        Map<String, String> current = null;
        for (String rawLine : info.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                current = new LinkedHashMap<>();
                sections.put(line.substring(1).trim().toLowerCase(), current);
            } else if (current != null && line.contains(":")) {
                int idx = line.indexOf(':');
                current.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
        }
        return sections;
    }

    /**
     * keyspace 行解析：{@code keys=1,expires=0,avg_ttl=0}。
     */
    public static Map<String, String> parseKeyValue(String s) {
        Map<String, String> result = new HashMap<>();
        if (s == null) {
            return result;
        }
        for (String pair : s.split(",")) {
            if (pair.contains("=")) {
                String[] kv = pair.split("=", 2);
                result.put(kv[0].trim(), kv[1].trim());
            }
        }
        return result;
    }

    /**
     * keyspace 分节 → 总 key 数。
     */
    public static long totalKeysFromKeyspace(Map<String, String> keyspace) {
        long total = 0;
        for (Map.Entry<String, String> entry : keyspace.entrySet()) {
            if (entry.getKey().startsWith("db")) {
                total += parseLong(parseKeyValue(entry.getValue()).get("keys"));
            }
        }
        return total;
    }

    public static long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static int parseInt(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static double parseDouble(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024L * 1024) {
            return String.format("%.1fK", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1fM", bytes / (1024.0 * 1024));
        }
        return String.format("%.2fG", bytes / (1024.0 * 1024 * 1024));
    }

    static Map<String, String> orEmpty(Map<String, String> map) {
        return map == null ? Map.of() : map;
    }

    private List<ClientInfoVO> parseClientList(String clientsRaw) {
        List<ClientInfoVO> result = new ArrayList<>();
        if (clientsRaw == null || clientsRaw.isBlank()) {
            return result;
        }
        for (String rawLine : clientsRaw.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            Map<String, String> fields = new HashMap<>();
            for (String pair : line.split(" ")) {
                if (pair.contains("=")) {
                    String[] kv = pair.split("=", 2);
                    fields.put(kv[0], kv[1]);
                }
            }
            result.add(ClientInfoVO.builder()
                    .id(fields.get("id"))
                    .addr(fields.get("addr"))
                    .db(fields.get("db"))
                    .age(fields.get("age"))
                    .idle(fields.get("idle"))
                    .cmd(fields.get("cmd"))
                    .build());
        }
        return result;
    }

    private List<SlowLogVO> parseSlowLog(List<Object> entries) {
        List<SlowLogVO> result = new ArrayList<>();
        if (entries == null) {
            return result;
        }
        for (Object entry : entries) {
            if (!(entry instanceof List<?> list) || list.size() < 4) {
                continue;
            }
            SlowLogVO vo = new SlowLogVO();
            if (list.get(0) instanceof Number n) {
                vo.setId(n.longValue());
            }
            if (list.get(1) instanceof Number n) {
                LocalDateTime ts = LocalDateTime.ofInstant(Instant.ofEpochSecond(n.longValue()), ZoneId.systemDefault());
                vo.setTimestamp(ts.toString());
            }
            if (list.get(2) instanceof Number n) {
                vo.setDurationUs(n.longValue());
            }
            if (list.get(3) instanceof List<?> cmdList) {
                vo.setCommand(cmdList.stream().map(Object::toString).collect(Collectors.joining(" ")));
            }
            result.add(vo);
        }
        return result;
    }
}
