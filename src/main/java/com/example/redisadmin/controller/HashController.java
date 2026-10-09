package com.example.redisadmin.controller;

import com.example.redisadmin.model.dto.HashBatchSetDTO;
import com.example.redisadmin.model.dto.HashFieldDTO;
import com.example.redisadmin.model.vo.ApiResponse;
import com.example.redisadmin.model.vo.HashFieldVO;
import com.example.redisadmin.model.vo.HashScanVO;
import com.example.redisadmin.service.HashService;
import com.example.redisadmin.web.ProfileId;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hash 端点 {@code /api/c/{id}/hashes/{key}}。HSCAN 游标分页。
 */
@RestController
@RequestMapping("/api/c/{id}/hashes/{key}")
public class HashController {

    private final HashService hashService;

    public HashController(HashService hashService) {
        this.hashService = hashService;
    }

    /**
     * HSCAN 分页。不传 {@code count} 视为无界全量请求，
     * 大 Key（元素数 &gt; large-key-threshold）将被拒绝 → 40001（验收 §15.8）。
     */
    @GetMapping
    public ApiResponse<HashScanVO> scan(
            @ProfileId Long profileId,
            @PathVariable String key,
            @RequestParam(defaultValue = "0") int db,
            @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(defaultValue = "*") String match,
            @RequestParam(required = false) Integer count) {
        return ApiResponse.success(hashService.scan(profileId, db, key, cursor, match, count));
    }

    @GetMapping("/fields/{field}")
    public ApiResponse<HashFieldVO> getField(
            @ProfileId Long profileId,
            @PathVariable String key,
            @PathVariable String field,
            @RequestParam(defaultValue = "0") int db) {
        String value = hashService.getField(profileId, db, key, field);
        return ApiResponse.success(HashFieldVO.builder().field(field).value(value).truncated(false).build());
    }

    @PostMapping("/fields")
    public ApiResponse<Void> setField(
            @ProfileId Long profileId,
            @PathVariable String key,
            @RequestParam(defaultValue = "0") int db,
            @Valid @RequestBody HashFieldDTO dto) {
        hashService.setField(profileId, db, key, dto.getField(), dto.getValue(), dto.getTtlSeconds());
        return ApiResponse.success();
    }

    @PutMapping("/fields")
    public ApiResponse<Void> batchSet(
            @ProfileId Long profileId,
            @PathVariable String key,
            @RequestParam(defaultValue = "0") int db,
            @Valid @RequestBody HashBatchSetDTO dto) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (HashFieldDTO entry : dto.getEntries()) {
            entries.put(entry.getField(), entry.getValue());
        }
        hashService.batchSet(profileId, db, key, entries, dto.getTtlSeconds());
        return ApiResponse.success();
    }

    @DeleteMapping("/fields/{field}")
    public ApiResponse<Map<String, Long>> deleteField(
            @ProfileId Long profileId,
            @PathVariable String key,
            @PathVariable String field,
            @RequestParam(defaultValue = "0") int db) {
        return ApiResponse.success(Map.of("deleted", hashService.deleteField(profileId, db, key, field)));
    }
}
