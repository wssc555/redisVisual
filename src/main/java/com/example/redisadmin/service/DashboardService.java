package com.example.redisadmin.service;

import com.example.redisadmin.config.AppProperties;
import com.example.redisadmin.config.CacheConfig;
import com.example.redisadmin.exception.BizException;
import com.example.redisadmin.exception.ErrorCode;
import com.example.redisadmin.model.vo.ClusterNodeVO;
import com.example.redisadmin.model.vo.ClusterTopologyVO;
import com.example.redisadmin.model.vo.DashboardOverviewVO;
import com.example.redisadmin.model.vo.InstanceMetricsVO;
import com.example.redisadmin.model.vo.LoadTrendVO;
import com.example.redisadmin.model.vo.MemoryTrendVO;
import com.example.redisadmin.redis.DeployMode;
import com.example.redisadmin.redis.ProfileConnection;
import com.example.redisadmin.redis.ProfileConnectionManager;
import com.example.redisadmin.redis.ProfileState;
import com.example.redisadmin.redis.RedisProfile;
import com.example.redisadmin.storage.ProfileRow;
import com.example.redisadmin.storage.ProfileStore;
import com.github.benmanes.caffeine.cache.Cache;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨实例仪表盘：聚合 + Caffeine 短 TTL 缓存 + 趋势环形缓冲采样。
 * <p>缓存 key 常量集中在 {@link CacheConfig}，避免散落字面量。</p>
 * <p>趋势为内存环形缓冲（默认容量 60 点 ≈ 5min），<b>不落库、重启清零</b>。</p>
 * <p>CPU 占用率：{@code INFO cpu} 的 {@code used_cpu_sys + used_cpu_user} 为累计秒数，
 * 由采样任务差分计算（Δcpu / Δwall × 100）。快照与结果只存内存，重启后需 2 个
 * 采样点（约 10s）才恢复显示。集群按 master 节点逐个采样，卡片展示各 master 均值
 * （与内存/键数只算 master 的口径一致）。</p>
 */
@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final ProfileConnectionManager connectionManager;
    private final ProfileStore profileStore;
    private final Cache<String, Object> dashboardCache;
    private final int sampleBufferSize;

    private final Deque<MemoryTrendVO.MemorySampleVO> memorySamples = new ArrayDeque<>();
    private final Deque<LoadTrendVO.LoadSampleVO> loadSamples = new ArrayDeque<>();

    /**
     * CPU 差分快照：节点键 → 上次采样（毫秒时间戳 + 累计 CPU 秒数）。
     */
    private final ConcurrentHashMap<String, CpuSnapshot> lastCpuSnapshots = new ConcurrentHashMap<>();

    /**
     * CPU 占用率最新结果：节点键 → 百分比（四舍五入到 0.1）。
     */
    private final ConcurrentHashMap<String, Double> cpuPercentByNode = new ConcurrentHashMap<>();

    /**
     * 节点键前缀：s=单机/哨兵实例级，c=集群节点级。
     */
    private static final String CPU_KEY_STANDALONE = "s:";
    private static final String CPU_KEY_CLUSTER = "c:";

    private record CpuSnapshot(long epochMs, double cpuSeconds) {
    }

    public DashboardService(ProfileConnectionManager connectionManager,
                            ProfileStore profileStore,
                            Cache<String, Object> dashboardCache,
                            AppProperties properties) {
        this.connectionManager = connectionManager;
        this.profileStore = profileStore;
        this.dashboardCache = dashboardCache;
        this.sampleBufferSize = properties.getDashboard().getSampleBufferSize();
    }

    /** 跨实例聚合（缓存 TTL 默认 5s，避免高频轮询打穿 Redis）。 */
    public DashboardOverviewVO getOverview() {
        Object cached = dashboardCache.getIfPresent(CacheConfig.DASHBOARD_OVERVIEW);
        if (cached instanceof DashboardOverviewVO vo) {
            return vo;
        }
        long start = System.currentTimeMillis();
        DashboardOverviewVO vo = buildOverview();
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed > 1000) {
            log.warn("仪表盘聚合慢操作: 耗时={}ms 实例数={}", elapsed, vo.getTotalProfiles());
        }
        dashboardCache.put(CacheConfig.DASHBOARD_OVERVIEW, vo);
        return vo;
    }

    private DashboardOverviewVO buildOverview() {
        List<ProfileRow> rows = profileStore.findAll();
        List<InstanceMetricsVO> instances = new ArrayList<>(rows.size());

        long memory = 0;
        long peak = 0;
        long clients = 0;
        long ops = 0;
        long keys = 0;
        int online = 0;

        for (ProfileRow row : rows) {
            InstanceMetricsVO vo = collectInstanceMetrics(row);
            instances.add(vo);
            if (ProfileState.CONNECTED.name().equals(vo.getProfileState())) {
                online++;
                memory += nz(vo.getMemoryUsageBytes());
                peak += nz(vo.getPeakMemoryBytes());
                clients += nz(vo.getClients());
                ops += nz(vo.getOpsPerSec());
                keys += nz(vo.getTotalKeys());
            }
        }

        return DashboardOverviewVO.builder()
                .totalProfiles(rows.size())
                .onlineProfiles(online)
                .offlineProfiles(rows.size() - online)
                .memoryUsageBytes(memory)
                .memoryUsageHuman(ServerService.formatBytes(memory))
                .peakMemoryBytes(peak)
                .clients(clients)
                .opsPerSec(ops)
                .totalKeys(keys)
                .instances(instances)
                .build();
    }

    /**
     * 采单实例指标。任何异常都降级为「离线 + 归零」，不抛出。
     */
    private InstanceMetricsVO collectInstanceMetrics(ProfileRow row) {
        long start = System.currentTimeMillis();
        try {
            RedisProfile profile = connectionManager.requireProfile(row.getId());
            boolean connected = profile.getMode() == DeployMode.CLUSTER
                    ? clusterHealthy(profile)
                    : standaloneHealthy(profile);

            if (!connected) {
                return offlineMetrics(row, null);
            }
            return profile.getMode() == DeployMode.CLUSTER
                    ? clusterMetrics(row, profile, System.currentTimeMillis() - start)
                    : standaloneMetrics(row, profile, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("采集实例指标失败: id={} name={} - {}",
                    row.getId(), row.getName(), e.getMessage());
            return offlineMetrics(row, null);
        }
    }

    private InstanceMetricsVO offlineMetrics(ProfileRow row, Long latencyMs) {
        return InstanceMetricsVO.builder()
                .profileId(row.getId())
                .name(row.getName())
                .mode(row.getMode().name())
                .profileState(ProfileState.OFFLINE.name())
                .nodeCount(0)
                .masterCount(0)
                .slaveCount(0)
                .memoryUsageBytes(0L)
                .memoryUsageHuman("0B")
                .peakMemoryBytes(0L)
                .maxMemoryBytes(0L)
                .memoryUsagePercent(0.0)
                .clients(0)
                .opsPerSec(0L)
                .cpuUsagePercent(0.0)
                .totalKeys(0L)
                .hitRate(0.0)
                .latencyMs(latencyMs)
                .build();
    }

    private boolean standaloneHealthy(RedisProfile profile) {
        try {
            RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(
                    profile.getId(), profile.getDatabaseOrDefault());
            return "PONG".equalsIgnoreCase(commands.ping());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean clusterHealthy(RedisProfile profile) {
        try {
            String info = connectionManager.getClusterCommands(profile.getId()).clusterInfo();
            return info != null && info.contains("cluster_state:ok");
        } catch (Exception e) {
            return false;
        }
    }

    private InstanceMetricsVO standaloneMetrics(ProfileRow row, RedisProfile profile, long latencyMs) {
        RedisClusterCommands<String, String> commands =
                connectionManager.getSyncCommands(profile.getId(), profile.getDatabaseOrDefault());
        // ⚠️ INFO 的 section 参数只能是单个分节名（lettuce 把参数原样作为一个 RESP 参数发送）。
        // 逗号拼接（如 "memory,stats"）会被 Redis 当作未知分节 → 返回空串 → 所有指标解析为 0。
        // 全量 INFO 一次拿齐，与 ServerService.getOverview 的 info("all") 同口径。
        Map<String, Map<String, String>> sections = ServerService.parseInfoSections(commands.info());

        Map<String, String> memory = sections.getOrDefault("memory", Collections.emptyMap());
        Map<String, String> stats = sections.getOrDefault("stats", Collections.emptyMap());
        Map<String, String> clients = sections.getOrDefault("clients", Collections.emptyMap());
        Map<String, String> server = sections.getOrDefault("server", Collections.emptyMap());
        Map<String, String> replication = sections.getOrDefault("replication", Collections.emptyMap());
        Map<String, String> keyspace = sections.getOrDefault("keyspace", Collections.emptyMap());

        long usedMem = ServerService.parseLong(memory.get("used_memory"));
        long maxMem = ServerService.parseLong(memory.get("maxmemory"));

        return InstanceMetricsVO.builder()
                .profileId(row.getId())
                .name(row.getName())
                .mode(profile.getMode().name())
                .profileState(ProfileState.CONNECTED.name())
                .nodeCount(1)
                .masterCount("master".equals(replication.get("role")) ? 1 : 0)
                .slaveCount(ServerService.parseInt(replication.get("connected_slaves")))
                .memoryUsageBytes(usedMem)
                .memoryUsageHuman(ServerService.formatBytes(usedMem))
                .peakMemoryBytes(ServerService.parseLong(memory.get("used_memory_peak")))
                .maxMemoryBytes(maxMem)
                .memoryUsagePercent(maxMem > 0 ? (double) usedMem / maxMem * 100 : 0.0)
                .clients(ServerService.parseInt(clients.get("connected_clients")))
                .opsPerSec(ServerService.parseLong(stats.get("instantaneous_ops_per_sec")))
                .cpuUsagePercent(cpuPercentByNode.get(CPU_KEY_STANDALONE + profile.getId()))
                .totalKeys(ServerService.totalKeysFromKeyspace(keyspace))
                .hitRate(hitRate(stats))
                .latencyMs(latencyMs)
                .build();
    }

    private InstanceMetricsVO clusterMetrics(ProfileRow row, RedisProfile profile, long latencyMs) {
        ProfileConnection connection = connectionManager.getConnection(profile.getId());
        String clusterInfo = connection.getClusterCommands().clusterInfo();
        Map<String, String> infoMap = new HashMap<>(ServerService.parseKeyValue(clusterInfo));

        int masters = 0;
        int replicas = 0;
        long usedMem = 0;
        long peakMem = 0;
        long maxMem = 0;
        long clients = 0;
        long ops = 0;
        long keys = 0;
        long hits = 0;
        long misses = 0;
        long uptimeDays = 0;
        // master 节点 CPU% 均值（与内存/键数只算 master 口径一致）
        double cpuSum = 0;
        int cpuNodes = 0;

        for (RedisClusterNode node : connection.getClusterClient().getPartitions()) {
            if (!node.isConnected()) {
                continue;
            }
            if (node.getRole().isMaster()) {
                masters++;
            } else {
                replicas++;
            }
            try {
                StatefulRedisConnection<String, String> conn = connection.getClusterConnection()
                        .getConnection(node.getUri().getHost(), node.getUri().getPort());
                // 同上：section 不能逗号拼接，全量 INFO 一次拿齐
                Map<String, Map<String, String>> sections = ServerService.parseInfoSections(
                        conn.sync().info());
                Map<String, String> memory = sections.getOrDefault("memory", Collections.emptyMap());
                Map<String, String> stats = sections.getOrDefault("stats", Collections.emptyMap());
                Map<String, String> nodeClients = sections.getOrDefault("clients", Collections.emptyMap());
                Map<String, String> server = sections.getOrDefault("server", Collections.emptyMap());

                // 内存/键数只算 master（副本会重复计数）
                if (node.getRole().isMaster()) {
                    usedMem += ServerService.parseLong(memory.get("used_memory"));
                    peakMem += ServerService.parseLong(memory.get("used_memory_peak"));
                    maxMem += ServerService.parseLong(memory.get("maxmemory"));
                    keys += ServerService.totalKeysFromKeyspace(
                            sections.getOrDefault("keyspace", Collections.emptyMap()));
                    hits += ServerService.parseLong(stats.get("keyspace_hits"));
                    misses += ServerService.parseLong(stats.get("keyspace_misses"));
                    Double cpu = cpuPercentByNode.get(clusterCpuKey(profile.getId(),
                            node.getUri().getHost(), node.getUri().getPort()));
                    if (cpu != null) {
                        cpuSum += cpu;
                        cpuNodes++;
                    }
                }
                clients += ServerService.parseInt(nodeClients.get("connected_clients"));
                ops += ServerService.parseLong(stats.get("instantaneous_ops_per_sec"));
                uptimeDays = Math.max(uptimeDays, ServerService.parseLong(server.get("uptime_in_days")));
            } catch (Exception e) {
                // 单节点失败不拖垮整体
                log.warn("采集集群节点指标失败: {} - {}", node.getUri(), e.getMessage());
            }
        }

        return InstanceMetricsVO.builder()
                .profileId(row.getId())
                .name(row.getName())
                .mode(profile.getMode().name())
                .profileState(ProfileState.CONNECTED.name())
                .nodeCount(masters + replicas)
                .masterCount(masters)
                .slaveCount(replicas)
                .clusterState(infoMap.getOrDefault("cluster_state", "unknown"))
                .memoryUsageBytes(usedMem)
                .memoryUsageHuman(ServerService.formatBytes(usedMem))
                .peakMemoryBytes(peakMem)
                .maxMemoryBytes(maxMem)
                .memoryUsagePercent(maxMem > 0 ? (double) usedMem / maxMem * 100 : 0.0)
                .clients((int) clients)
                .opsPerSec(ops)
                .cpuUsagePercent(cpuNodes > 0 ? round1(cpuSum / cpuNodes) : null)
                .totalKeys(keys)
                .hitRate(calcHitRate(hits, misses))
                .latencyMs(latencyMs)
                .build();
    }

    /**
     * 集群/哨兵节点拓扑；profile 不存在 → 40401，单机模式 → 40001（建连前即拒绝）。
     */
    public ClusterTopologyVO getTopology(Long profileId) {
        String key = CacheConfig.DASHBOARD_TOPOLOGY_PREFIX + profileId;
        Object cached = dashboardCache.getIfPresent(key);
        if (cached instanceof ClusterTopologyVO vo) {
            return vo;
        }

        RedisProfile profile = connectionManager.requireProfile(profileId);
        if (profile.getMode() == DeployMode.STANDALONE) {
            throw BizException.of(ErrorCode.VALIDATION_ERROR, "单机模式无拓扑概念，仅集群/哨兵支持");
        }
        ClusterTopologyVO vo = profile.getMode() == DeployMode.CLUSTER
                ? clusterTopology(profile)
                : sentinelTopology(profile);

        dashboardCache.put(key, vo);
        return vo;
    }

    private ClusterTopologyVO clusterTopology(RedisProfile profile) {
        ProfileConnection connection = connectionManager.getConnection(profile.getId());
        Map<String, String> infoMap = new HashMap<>(
                ServerService.parseKeyValue(connection.getClusterCommands().clusterInfo()));

        List<ClusterNodeVO> nodes = new ArrayList<>();
        for (RedisClusterNode node : connection.getClusterClient().getPartitions()) {
            ClusterNodeVO nodeVO = ClusterNodeVO.builder()
                    .id(node.getNodeId())
                    .addr(node.getUri().getHost() + ":" + node.getUri().getPort())
                    .role(node.getRole().isMaster() ? "MASTER" : "REPLICA")
                    .flags(List.of(node.getRole().name().toLowerCase()))
                    .slots(formatSlotRanges(node.getSlots()))
                    .connected(node.isConnected())
                    .masterId(node.getSlaveOf())
                    .memoryUsageBytes(0L)
                    .memoryUsageHuman("0B")
                    .clients(0)
                    .opsPerSec(0L)
                    .build();
            try {
                StatefulRedisConnection<String, String> conn = connection.getClusterConnection()
                        .getConnection(node.getUri().getHost(), node.getUri().getPort());
                Map<String, Map<String, String>> sections = ServerService.parseInfoSections(
                        conn.sync().info());
                Map<String, String> memory = sections.getOrDefault("memory", Collections.emptyMap());
                long usedMem = ServerService.parseLong(memory.get("used_memory"));
                nodeVO.setMemoryUsageBytes(usedMem);
                nodeVO.setMemoryUsageHuman(ServerService.formatBytes(usedMem));
                nodeVO.setClients(ServerService.parseInt(
                        sections.getOrDefault("clients", Collections.emptyMap()).get("connected_clients")));
                nodeVO.setOpsPerSec(ServerService.parseLong(
                        sections.getOrDefault("stats", Collections.emptyMap()).get("instantaneous_ops_per_sec")));
            } catch (Exception e) {
                log.warn("采集拓扑节点指标失败: {} - {}", node.getUri(), e.getMessage());
            }
            nodes.add(nodeVO);
        }

        return ClusterTopologyVO.builder()
                .profileId(profile.getId())
                .name(profile.getName())
                .mode(profile.getMode().name())
                .state(infoMap.getOrDefault("cluster_state", "unknown"))
                .slotsAssigned(ServerService.parseInt(infoMap.get("cluster_slots_assigned")))
                .slotsOk(ServerService.parseInt(infoMap.get("cluster_slots_ok")))
                .nodes(nodes)
                .build();
    }

    private ClusterTopologyVO sentinelTopology(RedisProfile profile) {
        ClusterTopologyVO vo = ClusterTopologyVO.builder()
                .profileId(profile.getId())
                .name(profile.getName())
                .mode(profile.getMode().name())
                .slotsAssigned(0)
                .slotsOk(0)
                .nodes(new ArrayList<>())
                .build();
        try {
            RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(profile.getId(), 0);
            Map<String, String> replication = ServerService.parseInfoSections(commands.info("replication"))
                    .getOrDefault("replication", Collections.emptyMap());

            vo.setState(replication.getOrDefault("role", "unknown"));
            vo.getNodes().add(ClusterNodeVO.builder()
                    .id("master")
                    .addr(profile.endpoint())
                    .role("MASTER")
                    .flags(List.of("master"))
                    .connected(true)
                    .memoryUsageBytes(0L)
                    .memoryUsageHuman("0B")
                    .clients(ServerService.parseInt(
                            ServerService.parseInfoSections(commands.info("clients"))
                                    .getOrDefault("clients", Collections.emptyMap())
                                    .get("connected_clients")))
                    .opsPerSec(ServerService.parseLong(
                            ServerService.parseInfoSections(commands.info("stats"))
                                    .getOrDefault("stats", Collections.emptyMap())
                                    .get("instantaneous_ops_per_sec")))
                    .build());

            int slaveCount = ServerService.parseInt(replication.get("connected_slaves"));
            for (int i = 0; i < slaveCount; i++) {
                String slaveInfo = replication.get("slave" + i);
                if (slaveInfo == null) {
                    continue;
                }
                Map<String, String> fields = ServerService.parseKeyValue(slaveInfo);
                vo.getNodes().add(ClusterNodeVO.builder()
                        .id("replica" + i)
                        .addr(fields.get("ip") + ":" + fields.get("port"))
                        .role("REPLICA")
                        .flags(List.of("slave"))
                        .connected("online".equals(fields.get("state")))
                        .masterId("master")
                        .memoryUsageBytes(0L)
                        .memoryUsageHuman("0B")
                        .clients(0)
                        .opsPerSec(0L)
                        .build());
            }
        } catch (Exception e) {
            log.warn("采集哨兵拓扑失败: id={} - {}", profile.getId(), e.getMessage());
            vo.setState("error");
        }
        return vo;
    }

    @Scheduled(fixedRateString = "${redis-admin.dashboard.sample-interval-ms:5000}")
    public void sample() {
        List<ProfileRow> rows = profileStore.findAll();
        LocalDateTime now = LocalDateTime.now();

        List<MemoryTrendVO.MemoryPointVO> memoryPoints = new ArrayList<>();
        List<LoadTrendVO.LoadPointVO> loadPoints = new ArrayList<>();

        for (ProfileRow row : rows) {
            // 只采内存态在线实例（与心跳口径一致）
            if (connectionManager.getState(row.getId()) != ProfileState.CONNECTED) {
                continue;
            }
            try {
                RedisProfile profile = connectionManager.requireProfile(row.getId());
                Map<String, Map<String, String>> sections = null;
                if (profile.getMode() == DeployMode.CLUSTER) {
                    // 集群：逐 master 节点采样 CPU（卡片 CPU 取 master 均值）
                    ProfileConnection connection = connectionManager.getConnection(profile.getId());
                    for (RedisClusterNode node : connection.clusterMasters()) {
                        try {
                            Map<String, Map<String, String>> nodeSections = ServerService.parseInfoSections(
                                    connection.nodeConnection(node.getUri().getHost(), node.getUri().getPort())
                                            .sync().info("cpu"));
                            sampleCpu(clusterCpuKey(profile.getId(), node.getUri().getHost(), node.getUri().getPort()),
                                    nodeSections.getOrDefault("cpu", Collections.emptyMap()));
                        } catch (Exception nodeEx) {
                            log.debug("集群节点 CPU 采样跳过: {} - {}", node.getUri(), nodeEx.getMessage());
                        }
                    }
                    // 趋势点位维持既有口径（聚合入口单节点值）
                } else {
                    // 单机/哨兵：一次全量 INFO（section 不能逗号拼接，见 standaloneMetrics 注释）
                    // 同时供 CPU 采样与趋势点位，避免重复往返
                    sections = ServerService.parseInfoSections(
                            connectionManager.getSyncCommands(profile.getId(), profile.getDatabaseOrDefault())
                                    .info());
                    sampleCpu(CPU_KEY_STANDALONE + profile.getId(),
                            sections.getOrDefault("cpu", Collections.emptyMap()));
                }
                if (sections == null) {
                    RedisClusterCommands<String, String> commands = connectionManager.getSyncCommands(
                            profile.getId(), profile.getDatabaseOrDefault());
                    sections = ServerService.parseInfoSections(commands.info());
                }
                memoryPoints.add(MemoryTrendVO.MemoryPointVO.builder()
                        .profileId(row.getId())
                        .name(row.getName())
                        .usedMemoryBytes(ServerService.parseLong(
                                sections.getOrDefault("memory", Collections.emptyMap()).get("used_memory")))
                        .build());
                loadPoints.add(LoadTrendVO.LoadPointVO.builder()
                        .profileId(row.getId())
                        .name(row.getName())
                        .opsPerSec(ServerService.parseLong(
                                sections.getOrDefault("stats", Collections.emptyMap()).get("instantaneous_ops_per_sec")))
                        .clients(ServerService.parseInt(
                                sections.getOrDefault("clients", Collections.emptyMap()).get("connected_clients")))
                        .build());
            } catch (Exception e) {
                log.debug("采样跳过: id={} - {}", row.getId(), e.getMessage());
            }
        }

        synchronized (memorySamples) {
            memorySamples.addLast(MemoryTrendVO.MemorySampleVO.builder()
                    .timestamp(now).points(memoryPoints).build());
            while (memorySamples.size() > sampleBufferSize) {
                memorySamples.removeFirst();
            }
        }
        synchronized (loadSamples) {
            loadSamples.addLast(LoadTrendVO.LoadSampleVO.builder()
                    .timestamp(now).points(loadPoints).build());
            while (loadSamples.size() > sampleBufferSize) {
                loadSamples.removeFirst();
            }
        }
    }

    public MemoryTrendVO getMemoryTrend(int seconds) {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(Math.max(1, seconds));
        List<MemoryTrendVO.MemorySampleVO> samples;
        synchronized (memorySamples) {
            samples = memorySamples.stream()
                    .filter(s -> s.getTimestamp().isAfter(cutoff))
                    .toList();
        }
        return MemoryTrendVO.builder().samples(samples).build();
    }

    public LoadTrendVO getLoadTrend(int seconds) {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(Math.max(1, seconds));
        List<LoadTrendVO.LoadSampleVO> samples;
        synchronized (loadSamples) {
            samples = loadSamples.stream()
                    .filter(s -> s.getTimestamp().isAfter(cutoff))
                    .toList();
        }
        return LoadTrendVO.builder().samples(samples).build();
    }

    /**
     * 配置变更后清空仪表盘缓存与趋势缓冲，避免展示过期数据。
     */
    public void evict(Long profileId) {
        dashboardCache.invalidate(CacheConfig.DASHBOARD_OVERVIEW);
        dashboardCache.invalidate(CacheConfig.DASHBOARD_TOPOLOGY_PREFIX + profileId);
    }

    /**
     * 集群节点 CPU 快照键：{@code c:{profileId}:{host}:{port}}。
     */
    private static String clusterCpuKey(Long profileId, String host, int port) {
        return CPU_KEY_CLUSTER + profileId + ":" + host + ":" + port;
    }

    /**
     * CPU 差分采样：{@code used_cpu_sys + used_cpu_user} 累计秒数 → 区间占用率（%）。
     * <ul>
     *   <li>首个采样点只记快照不产出（无差分基线）</li>
     *   <li>计数器回绕（Redis 重启 / 主从切换后 rdb 载入）→ 负差分丢弃，仅刷新基线</li>
     * </ul>
     */
    private void sampleCpu(String nodeKey, Map<String, String> cpuSection) {
        double cpuSeconds = parseDouble(cpuSection.get("used_cpu_sys"))
                + parseDouble(cpuSection.get("used_cpu_user"));
        long now = System.currentTimeMillis();
        CpuSnapshot prev = lastCpuSnapshots.put(nodeKey, new CpuSnapshot(now, cpuSeconds));
        if (prev == null || now <= prev.epochMs()) {
            return;
        }
        double deltaCpu = cpuSeconds - prev.cpuSeconds();
        if (deltaCpu < 0) {
            return;
        }
        double wallSeconds = (now - prev.epochMs()) / 1000.0;
        double percent = deltaCpu / wallSeconds * 100.0;
        cpuPercentByNode.put(nodeKey, round1(percent));
    }

    private static double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private List<String> formatSlotRanges(List<Integer> slots) {
        if (slots == null || slots.isEmpty()) {
            return List.of();
        }
        List<Integer> sorted = new ArrayList<>(slots);
        Collections.sort(sorted);
        List<String> ranges = new ArrayList<>();
        int start = sorted.get(0);
        int prev = start;
        for (int i = 1; i < sorted.size(); i++) {
            int curr = sorted.get(i);
            if (curr != prev + 1) {
                ranges.add(start == prev ? String.valueOf(start) : start + "-" + prev);
                start = curr;
            }
            prev = curr;
        }
        ranges.add(start == prev ? String.valueOf(start) : start + "-" + prev);
        return ranges;
    }

    private Double hitRate(Map<String, String> stats) {
        return calcHitRate(ServerService.parseLong(stats.get("keyspace_hits")),
                ServerService.parseLong(stats.get("keyspace_misses")));
    }

    private Double calcHitRate(long hits, long misses) {
        if (hits + misses <= 0) {
            return 0.0;
        }
        return Math.round((double) hits / (hits + misses) * 1000.0) / 1000.0;
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
