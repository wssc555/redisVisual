package com.example.redisadmin.controller;

import com.example.redisadmin.model.dto.ProfileDTO;
import com.example.redisadmin.model.dto.ProfileValidateDTO;
import com.example.redisadmin.model.vo.ApiResponse;
import com.example.redisadmin.model.vo.DatabaseInfoVO;
import com.example.redisadmin.model.vo.ProfileVO;
import com.example.redisadmin.model.vo.ValidateResultVO;
import com.example.redisadmin.service.ProfileRegistryService;
import com.example.redisadmin.service.ProfileValidationService;
import com.example.redisadmin.service.ServerService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 连接配置端点 {@code /api/profiles}（对应 kafkaVisual5 的 ClusterRegistryController）。
 * <p>响应只含 {@code credentialPresence}，<b>永不回密码</b>。</p>
 */
@RestController
@RequestMapping("/api/profiles")
public class ProfileRegistryController {

    private final ProfileRegistryService registryService;
    private final ProfileValidationService validationService;
    private final ServerService serverService;

    public ProfileRegistryController(ProfileRegistryService registryService,
                                     ProfileValidationService validationService,
                                     ServerService serverService) {
        this.registryService = registryService;
        this.validationService = validationService;
        this.serverService = serverService;
    }

    @GetMapping
    public ApiResponse<List<ProfileVO>> list() {
        return ApiResponse.success(registryService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<ProfileVO> get(@PathVariable Long id) {
        return ApiResponse.success(registryService.get(id));
    }

    @PostMapping
    public ApiResponse<ProfileVO> create(@Valid @RequestBody ProfileDTO dto) {
        return ApiResponse.success(registryService.create(dto));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProfileVO> update(@PathVariable Long id, @Valid @RequestBody ProfileDTO dto) {
        return ApiResponse.success(registryService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        registryService.delete(id);
        return ApiResponse.success();
    }

    /**
     * 测试连接。带 id 且省略密码时从库补全。
     */
    @PostMapping("/validate")
    public ApiResponse<ValidateResultVO> validate(@Valid @RequestBody ProfileValidateDTO dto) {
        return ApiResponse.success(validationService.validate(dto));
    }

    /**
     * {@code INFO keyspace} 解析出的各 db key 数；集群固定 {@code [{db:0, keys:总Key}]}。
     */
    @GetMapping("/{id}/databases")
    public ApiResponse<List<DatabaseInfoVO>> databases(@PathVariable Long id) {
        return ApiResponse.success(serverService.getDatabases(id));
    }
}
