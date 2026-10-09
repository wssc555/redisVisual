package com.example.redisadmin.config;

import com.example.redisadmin.redis.DeployMode;
import com.example.redisadmin.redis.ProfileConnection;
import com.example.redisadmin.redis.ProfileConnectionManager;
import com.example.redisadmin.redis.RedisProfile;
import com.example.redisadmin.storage.AppPaths;
import com.example.redisadmin.storage.ProfileRow;
import com.example.redisadmin.storage.ProfileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动只读汇报：逐个 profile 探活并打印结果。
 * <p><b>不 System.exit</b>：连不上也允许起服务改配置。
 * 探活逐个 try-catch，单实例失败不影响其他实例汇报，也不影响应用启动。</p>
 * <p>启动<b>不预连</b>：这里建的是临时连接，用完即关，不进 LRU 缓存。</p>
 */
@Component
@Order(10)
public class StartupValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupValidator.class);

    private final ProfileStore profileStore;
    private final ProfileConnectionManager connectionManager;
    private final AppPaths appPaths;

    public StartupValidator(ProfileStore profileStore,
                            ProfileConnectionManager connectionManager,
                            AppPaths appPaths) {
        this.profileStore = profileStore;
        this.connectionManager = connectionManager;
        this.appPaths = appPaths;
    }

    @Override
    public void run(ApplicationArguments args) {
        appPaths.logResolvedPaths();
        List<ProfileRow> rows = profileStore.findAll();
        if (rows.isEmpty()) {
            log.info("启动汇报: 尚无已配置的连接实例，可通过 POST /api/profiles 创建");
            return;
        }

        int reachable = 0;
        for (ProfileRow row : rows) {
            boolean ok = probe(row);
            if (ok) {
                reachable++;
            }
        }
        log.info("启动汇报: 共 {} 个实例，可达 {} 个，不可达 {} 个（不影响服务启动，可稍后编辑配置重试）",
                rows.size(), reachable, rows.size() - reachable);
    }

    private boolean probe(ProfileRow row) {
        ProfileConnection ephemeral = null;
        try {
            RedisProfile profile = profileStore.resolveProfile(row.getId());
            ephemeral = connectionManager.createEphemeral(profile);
            boolean ok = ephemeral.ping();
            log.info("启动汇报: id={} name={} mode={} endpoint={} → {}",
                    row.getId(), row.getName(), row.getMode(), describeEndpoint(profile), ok ? "可达" : "不可达");
            return ok;
        } catch (Exception e) {
            log.info("启动汇报: id={} name={} mode={} → 不可达（{}）",
                    row.getId(), row.getName(), row.getMode(), e.getMessage());
            return false;
        } finally {
            if (ephemeral != null) {
                try {
                    ephemeral.close();
                } catch (Exception ignored) {
                    // 汇报用的临时连接，关闭异常无需上抛
                }
            }
        }
    }

    private String describeEndpoint(RedisProfile profile) {
        if (profile.getMode() == DeployMode.CLUSTER) {
            return "cluster(seeds=" + profile.getNodes().size() + ")";
        }
        return profile.endpoint();
    }
}
