package com.example.redisadmin.service;

import com.example.redisadmin.exception.BizException;
import com.example.redisadmin.exception.ErrorCode;
import com.example.redisadmin.model.dto.ProfileDTO;
import com.example.redisadmin.model.vo.CredentialPresence;
import com.example.redisadmin.model.vo.ProfileVO;
import com.example.redisadmin.redis.DeployMode;
import com.example.redisadmin.redis.ProfileConnectionManager;
import com.example.redisadmin.storage.JsonCodec;
import com.example.redisadmin.storage.ProfileRow;
import com.example.redisadmin.storage.ProfileStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 连接配置 CRUD 与模式条件校验。
 * <p>校验口径：名称唯一（应用层 + 唯一索引双保险）；模式条件字段必填；
 * 集群强制 db0。</p>
 */
@Service
public class ProfileRegistryService {

    private final ProfileStore profileStore;
    private final ProfileConnectionManager connectionManager;
    private final DashboardService dashboardService;

    public ProfileRegistryService(ProfileStore profileStore,
                                  ProfileConnectionManager connectionManager,
                                  DashboardService dashboardService) {
        this.profileStore = profileStore;
        this.connectionManager = connectionManager;
        this.dashboardService = dashboardService;
    }

    public List<ProfileVO> list() {
        return profileStore.findAll().stream().map(this::toVO).toList();
    }

    public ProfileVO get(Long id) {
        return toVO(requireRow(id));
    }

    public ProfileVO create(ProfileDTO dto) {
        validate(dto, null);
        ProfileRow row = toRow(dto);
        ProfileRow created = profileStore.insert(row);
        return toVO(created);
    }

    public ProfileVO update(Long id, ProfileDTO dto) {
        requireRow(id);
        validate(dto, id);
        ProfileRow row = toRow(dto);
        ProfileRow updated = profileStore.update(id, row);
        // 配置变更后驱逐旧连接，下次访问懒重建；并清掉仪表盘缓存避免展示过期数据
        connectionManager.refresh(id);
        dashboardService.evict(id);
        return toVO(updated);
    }

    public void delete(Long id) {
        requireRow(id);
        // 先关连接再删配置，避免留下指向已删配置的僵尸连接
        connectionManager.close(id);
        profileStore.delete(id);
        dashboardService.evict(id);
    }

    /**
     * 模式条件字段校验 + 名称唯一。
     *
     * @param excludeId 更新场景下排除自身（改名时与自己同名不算重复）
     */
    private void validate(ProfileDTO dto, Long excludeId) {
        DeployMode mode = dto.getMode();
        if (mode == null) {
            throw BizException.of(ErrorCode.VALIDATION_ERROR, "部署模式不能为空");
        }
        switch (mode) {
            case STANDALONE -> {
                if (isBlank(dto.getHost())) {
                    throw BizException.of(ErrorCode.VALIDATION_ERROR, "单机模式必须配置 host");
                }
            }
            case CLUSTER -> {
                if (dto.getNodes() == null || dto.getNodes().isEmpty()) {
                    throw BizException.of(ErrorCode.VALIDATION_ERROR, "集群模式必须至少配置一个种子节点");
                }
            }
            case SENTINEL -> {
                if (dto.getSentinels() == null || dto.getSentinels().isEmpty()) {
                    throw BizException.of(ErrorCode.VALIDATION_ERROR, "哨兵模式必须至少配置一个哨兵节点");
                }
                if (isBlank(dto.getMasterName())) {
                    throw BizException.of(ErrorCode.VALIDATION_ERROR, "哨兵模式必须配置 masterName");
                }
            }
            default -> throw BizException.of(ErrorCode.VALIDATION_ERROR, "不支持的部署模式: " + mode);
        }

        if (profileStore.existsByName(dto.getName(), excludeId)) {
            throw BizException.of(ErrorCode.VALIDATION_ERROR, "连接名称已存在: " + dto.getName());
        }
    }

    private ProfileRow toRow(ProfileDTO dto) {
        ProfileRow row = new ProfileRow();
        row.setName(dto.getName());
        row.setMode(dto.getMode());
        row.setHost(dto.getHost());
        row.setPort(dto.getPort() != null ? dto.getPort() : (dto.getMode() == DeployMode.STANDALONE ? 6379 : null));
        row.setNodes(JsonCodec.writeNodeList(toNodeRows(dto.getNodes())));
        row.setSentinels(JsonCodec.writeNodeList(toNodeRows(dto.getSentinels())));
        row.setMasterName(dto.getMasterName());
        row.setDatabase(dto.getMode() == DeployMode.CLUSTER
                ? 0
                : (dto.getDatabase() == null ? 0 : dto.getDatabase()));
        row.setUsername(dto.getUsername());
        row.setPassword(dto.getPassword());
        row.setSentinelPassword(dto.getSentinelPassword());
        return row;
    }

    private List<ProfileRow.NodeRow> toNodeRows(List<ProfileDTO.NodeDTO> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        return nodes.stream()
                .map(n -> new ProfileRow.NodeRow(n.getHost(), n.getPort()))
                .toList();
    }

    private ProfileRow requireRow(Long id) {
        return profileStore.findRowById(id)
                .orElseThrow(() -> BizException.of(ErrorCode.PROFILE_NOT_FOUND, String.valueOf(id)));
    }

    private ProfileVO toVO(ProfileRow row) {
        return ProfileVO.builder()
                .id(row.getId())
                .name(row.getName())
                .mode(row.getMode())
                .host(row.getHost())
                .port(row.getPort())
                .nodes(row.nodeList().stream()
                        .map(n -> ProfileVO.NodeVO.builder().host(n.getHost()).port(n.getPort()).build())
                        .toList())
                .sentinels(row.sentinelList().stream()
                        .map(n -> ProfileVO.NodeVO.builder().host(n.getHost()).port(n.getPort()).build())
                        .toList())
                .masterName(row.getMasterName())
                .database(row.getDatabase())
                .username(row.getUsername())
                .credentialPresence(presence(row.getPassword()))
                .sentinelCredentialPresence(presence(row.getSentinelPassword()))
                .profileState(connectionManager.getState(row.getId()).name())
                .build();
    }

    private CredentialPresence presence(String cipher) {
        return ProfileStore.hasCredential(cipher) ? CredentialPresence.PRESENT : CredentialPresence.NONE;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
