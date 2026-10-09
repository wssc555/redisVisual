package com.example.redisadmin.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 收敛全部配置（替代散落的 {@code @Value}）。
 * 前缀 {@code redis-admin}，与 application.yml 中的层级一一对应。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "redis-admin")
public class AppProperties {

    /**
     * 存储配置（数据目录 / 方言 / 外部数据源）。
     */
    private Storage storage = new Storage();

    /**
     * 连接管理配置。
     */
    private Connection connection = new Connection();

    /**
     * 大 Key 与分页防护阈值。
     */
    private Limits limits = new Limits();

    /**
     * 仪表盘缓存与趋势采样。
     */
    private Dashboard dashboard = new Dashboard();

    /**
     * 操作日志（key_history）配置。
     */
    private History history = new History();

    /**
     * 实时数据检索（SCAN + 值匹配）配置。
     */
    private Search search = new Search();

    @Getter
    @Setter
    public static class Storage {
        /**
         * 存储方言：SQLITE / MYSQL / POSTGRES。
         */
        private String type = "SQLITE";
        /**
         * 数据库文件路径（SQLite 生效）；默认 {@code <cwd>/data/app.db}。
         */
        private String dbPath;
        /**
         * MySQL / PG 连接串（非 SQLite 方言生效）。
         */
        private String url;
        private String username;
        private String password;
    }

    @Getter
    @Setter
    public static class Connection {
        /**
         * 每个 profile 最多懒建的 db 连接数（单机/哨兵）。
         */
        private int maxConnectionsPerProfile = 16;
        /**
         * LRU 缓存的 profile 总数上限。
         */
        private int maxCachedProfiles = 10;
        /**
         * 缓存条目空闲多久后驱逐（分钟）。
         */
        private int expireAfterAccessMinutes = 30;
        /**
         * 心跳间隔（毫秒，整数值直接进 fixedRateString）。
         * 消费点：{@code ProfileConnectionManager#heartbeat} 的
         * {@code @Scheduled(fixedRateString="${redis-admin.connection.heartbeat-interval-ms:60000}")}
         * —— 修改默认值时两处必须同步（注解无法引用 Bean 字段）。
         */
        private long heartbeatIntervalMs = 60_000L;
        /**
         * 建连超时（毫秒）。
         */
        private long connectTimeoutMs = 5_000L;
        /**
         * 命令级超时（毫秒）。
         */
        private long commandTimeoutMs = 3_000L;
        /**
         * 集群拓扑刷新周期（秒）。
         */
        private int topologyRefreshPeriodSeconds = 60;
    }

    @Getter
    @Setter
    public static class Limits {
        /**
         * 容器元素数超过该值即拒绝无界全量请求。
         */
        private int largeKeyThreshold = 5000;
        /**
         * String / 单字段值超过该字节数即截断预览。
         */
        private int maxValueBytes = 1024 * 1024;
        /**
         * SCAN 单页 count 上限。
         */
        private int maxScanCount = 1000;
        /**
         * ZSet pageSize 上限。
         */
        private int maxZSetPageSize = 100;
        /**
         * LRANGE 区间长度上限（end - start + 1）。
         */
        private int maxListRange = 5000;
    }

    @Getter
    @Setter
    public static class Dashboard {
        /**
         * overview 缓存 TTL（秒）。
         */
        private int cacheTtlSeconds = 5;
        /**
         * 趋势采样间隔（毫秒）。
         * 消费点：{@code DashboardService#sample} 的
         * {@code @Scheduled(fixedRateString="${redis-admin.dashboard.sample-interval-ms:5000}")}
         * —— 修改默认值时两处必须同步（注解无法引用 Bean 字段）。
         */
        private long sampleIntervalMs = 5_000L;
        /**
         * 趋势环形缓冲容量（采样点个数）。
         */
        private int sampleBufferSize = 60;
    }

    @Getter
    @Setter
    public static class History {
        /**
         * value_preview 截断上限（字节）。
         * <p>超限只截预览，{@code value_bytes} 仍记原始长度；日志表内的 value 检索
         * 也只覆盖这 N 字节（设计 §3.2 已知边界）。</p>
         */
        private int previewBytes = 1024;
        /**
         * 记录保留天数；清理任务删除更早的行。
         */
        private int retentionDays = 30;
        /**
         * 查询接口 pageSize 上限（钳制用，防止 pageSize 过大打爆 LIKE 全表扫描）。
         */
        private int maxPageSize = 200;
        /**
         * 过期清理周期（毫秒，整数值直接进调度注解）。
         * 消费点：{@code KeyHistoryService#purge} 的
         * {@code @Scheduled(fixedDelayString="${redis-admin.history.purge-interval-ms:86400000}",
         * initialDelayString="...")}
         * —— 修改默认值时两处必须同步（注解无法引用 Bean 字段）。
         * <p>字段本身不被 Java 代码读取（与 heartbeat/sample 间隔同口径：只供注解消费），
         * 但仍保留 getter 以便运维 introspect 与未来可能的自适应清理使用。</p>
         */
        private long purgeIntervalMs = 86_400_000L;
    }

    @Getter
    @Setter
    public static class Search {
        /**
         * 单请求最多扫描的 key 数（预算封顶）；超限返回部分结果 + exhausted=false。
         */
        private int maxKeysPerRequest = 2000;
        /**
         * 容器类型每 key 最多检查的元素数（配合大 Key 防护，不做无界全量读）。
         */
        private int maxElementsPerKey = 100;
        /**
         * 单次返回的最大命中条数（limit 参数钳制上限）。
         */
        private int maxPageSize = 200;
    }
}
