package com.example.redisadmin.controller;

import com.example.redisadmin.model.dto.KeyRenameDTO;
import com.example.redisadmin.model.dto.KeyTtlDTO;
import com.example.redisadmin.model.vo.ApiResponse;
import com.example.redisadmin.model.vo.KeyDetailVO;
import com.example.redisadmin.model.vo.KeyScanVO;
import com.example.redisadmin.service.KeyService;
import com.example.redisadmin.web.ProfileId;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 通用 Key 端点 {@code /api/c/{id}/keys}。
 * <p>{@code {id}} 由 {@link ProfileId} + 参数解析器统一解析（缺失/非数字 → 40001；
 * 不存在 → 40401）。</p>
 */
@RestController
@RequestMapping("/api/c/{id}/keys")
public class KeyController {

    private final KeyService keyService;

    public KeyController(KeyService keyService) {
        this.keyService = keyService;
    }

    /**
     * SCAN 分页。{@code count} 默认 200，上限 1000；{@code cursor} 不透明，原样回传。
     * {@code type} 可选（string/list/hash/set/zset，Redis 6.0+ 服务端过滤）。
     */
    @GetMapping
    public ApiResponse<KeyScanVO> scanKeys(
            @ProfileId Long profileId,
            @RequestParam(defaultValue = "0") int db,
            @RequestParam(defaultValue = "0") String cursor,
            @RequestParam(required = false) String match,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "200") Integer count) {
        return ApiResponse.success(keyService.scanKeys(profileId, db, cursor, match, count, type));
    }

    /** 详情：type / size / ttl / encoding / memoryUsageBytes。 */
    @GetMapping("/{key}")
    public ApiResponse<KeyDetailVO> getKeyDetail(
            @ProfileId Long profileId,
            @PathVariable String key,
            @RequestParam(defaultValue = "0") int db) {
        return ApiResponse.success(keyService.getKeyDetail(profileId, db, key));
    }

    @DeleteMapping("/{key}")
    public ApiResponse<Map<String, Long>> deleteKey(
            @ProfileId Long profileId,
            @PathVariable String key,
            @RequestParam(defaultValue = "0") int db) {
        return ApiResponse.success(Map.of("deleted", keyService.deleteKey(profileId, db, key)));
    }

    /** 设 TTL：{@code -1} = 永久，key 不存在 → 40402。 */
    @PatchMapping("/{key}/ttl")
    public ApiResponse<Void> setTtl(
            @ProfileId Long profileId,
            @PathVariable String key,
            @RequestParam(defaultValue = "0") int db,
            @Valid @RequestBody KeyTtlDTO dto) {
        keyService.setTtl(profileId, db, key, dto.getTtlSeconds());
        return ApiResponse.success();
    }

    /** 重命名：目标已存在 → 40001（禁覆盖）。 */
    @PatchMapping("/{key}/rename")
    public ApiResponse<Void> renameKey(
            @ProfileId Long profileId,
            @PathVariable String key,
            @RequestParam(defaultValue = "0") int db,
            @Valid @RequestBody KeyRenameDTO dto) {
        keyService.renameKey(profileId, db, key, dto.getNewKey());
        return ApiResponse.success();
    }
}
