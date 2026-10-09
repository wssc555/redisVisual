package com.example.redisadmin.service;

import com.example.redisadmin.exception.BizException;
import com.example.redisadmin.exception.ErrorCode;
import com.example.redisadmin.model.dto.ProfileDTO;
import com.example.redisadmin.model.dto.ProfileValidateDTO;
import com.example.redisadmin.model.vo.ValidateResultVO;
import com.example.redisadmin.redis.DeployMode;
import com.example.redisadmin.redis.ProfileConnection;
import com.example.redisadmin.redis.ProfileConnectionManager;
import com.example.redisadmin.redis.RedisProfile;
import com.example.redisadmin.storage.ProfileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 测试连接：用<b>临时 client</b> 探活，成功即关。不进 LRU 缓存，不影响在线连接。
 * <p>带 {@code id} 且密码为 {@code "******"}/缺省时，从库取密文解密补全再测试
 * （对齐 kafkaVisual5 validate 的省略补全语义）。</p>
 */
@Service
public class ProfileValidationService {

    private static final Logger log = LoggerFactory.getLogger(ProfileValidationService.class);

    private final ProfileStore profileStore;
    private final ProfileConnectionManager connectionManager;

    public ProfileValidationService(ProfileStore profileStore, ProfileConnectionManager connectionManager) {
        this.profileStore = profileStore;
        this.connectionManager = connectionManager;
    }

    public ValidateResultVO validate(ProfileValidateDTO dto) {
        long start = System.currentTimeMillis();
        ProfileConnection ephemeral = null;
        try {
            RedisProfile profile = resolveProfile(dto);
            ephemeral = connectionManager.createEphemeral(profile);
            boolean healthy = ephemeral.ping();
            int nodeCount = nodeCount(profile, ephemeral);
            int latencyMs = (int) (System.currentTimeMillis() - start);

            if (healthy) {
                log.info("validate 通过: id={} name={} mode={} endpoint={} latency={}ms nodes={}",
                        profile.getId(), profile.getName(), profile.getMode(), profile.endpoint(), latencyMs, nodeCount);
            } else {
                log.info("validate 失败（探活不通过）: id={} name={} mode={} endpoint={}",
                        profile.getId(), profile.getName(), profile.getMode(), profile.endpoint());
            }
            return ValidateResultVO.builder()
                    .reachable(healthy)
                    .latencyMs(latencyMs)
                    .nodeCount(nodeCount)
                    .mode(profile.getMode().name())
                    .error(healthy ? null : "探活未通过（实例不可达或 cluster_state != ok）")
                    .build();
        } catch (Exception e) {
            int latencyMs = (int) (System.currentTimeMillis() - start);
            String error = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            log.info("validate 异常: {}（{}ms）", error, latencyMs);
            return ValidateResultVO.builder()
                    .reachable(false)
                    .latencyMs(latencyMs)
                    .nodeCount(0)
                    .mode(dto.getMode() == null ? null : dto.getMode().name())
                    .error(error)
                    .build();
        } finally {
            if (ephemeral != null) {
                try {
                    ephemeral.close();
                } catch (Exception ignored) {
                    // 临时连接的关闭异常无需上抛
                }
            }
        }
    }

    /**
     * 组装待测 profile：请求体字段优先；密码为占位/缺省时从库补全。
     * <p>带 id 时以库中已解密的 {@code RedisProfile} 为基线，省略字段与省略密码一并补全。</p>
     */
    private RedisProfile resolveProfile(ProfileValidateDTO dto) {
        if (dto.getMode() == null) {
            throw BizException.of(ErrorCode.VALIDATION_ERROR, "部署模式不能为空");
        }
        RedisProfile stored = dto.getId() == null ? null : profileStore.resolveProfile(dto.getId());

        return RedisProfile.builder()
                .id(dto.getId() == null ? -1L : dto.getId())
                .name(stored == null ? "validate-temp" : stored.getName())
                .mode(dto.getMode())
                .host(isBlank(dto.getHost()) && stored != null ? stored.getHost() : dto.getHost())
                .port(resolvePort(dto.getPort(), stored))
                .nodes(resolveNodes(dto.getNodes(), stored == null ? null : stored.getNodes(), 6379))
                .sentinels(resolveNodes(dto.getSentinels(), stored == null ? null : stored.getSentinels(), 26379))
                .masterName(isBlank(dto.getMasterName()) && stored != null ? stored.getMasterName() : dto.getMasterName())
                .database(dto.getMode() == DeployMode.CLUSTER ? 0
                        : (dto.getDatabase() == null ? 0 : dto.getDatabase()))
                .username(isBlank(dto.getUsername()) && stored != null ? stored.getUsername() : dto.getUsername())
                .password(resolveSecret(dto.getPassword(), stored == null ? null : stored.getPassword()))
                .sentinelPassword(resolveSecret(dto.getSentinelPassword(),
                        stored == null ? null : stored.getSentinelPassword()))
                .build();
    }

    /**
     * 密码三态在 validate 侧的语义：占位/缺省 → 用库中现值（已由 store 解密）；
     * 空串 → 明确表示「不带密码」；其他 → 用请求值。
     */
    private String resolveSecret(String input, String storedPlain) {
        if (input == null || ProfileStore.MASKED.equals(input)) {
            return storedPlain;
        }
        if (input.isEmpty()) {
            return null;
        }
        return input;
    }

    private List<RedisProfile.Node> resolveNodes(List<ProfileDTO.NodeDTO> request,
                                                 List<RedisProfile.Node> stored, int defaultPort) {
        if (request != null && !request.isEmpty()) {
            return request.stream()
                    .map(n -> RedisProfile.Node.builder()
                            .host(n.getHost())
                            .port(n.getPort() == null ? defaultPort : n.getPort())
                            .build())
                    .toList();
        }
        return stored == null ? List.of() : stored;
    }

    private static Integer resolvePort(Integer requestPort, RedisProfile stored) {
        if (requestPort != null && requestPort > 0) {
            return requestPort;
        }
        return stored == null ? null : stored.getPort();
    }

    private int nodeCount(RedisProfile profile, ProfileConnection connection) {
        if (profile.getMode() != DeployMode.CLUSTER) {
            return 1;
        }
        try {
            return connection.getClusterClient().getPartitions().size();
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
