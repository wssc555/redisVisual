package com.example.redisadmin.controller;

import com.example.redisadmin.model.dto.StringAppendDTO;
import com.example.redisadmin.model.dto.StringSetDTO;
import com.example.redisadmin.model.vo.ApiResponse;
import com.example.redisadmin.model.vo.StringValueVO;
import com.example.redisadmin.service.StringService;
import com.example.redisadmin.web.ProfileId;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * String 端点 {@code /api/c/{id}/strings}。
 * <p>GET 的值 &gt; 1MB 时服务端截断预览，回 {@code truncated:true} + 完整 length。</p>
 */
@RestController
@RequestMapping("/api/c/{id}/strings")
public class StringController {

    private final StringService stringService;

    public StringController(StringService stringService) {
        this.stringService = stringService;
    }

    @GetMapping("/{key}")
    public ApiResponse<StringValueVO> get(
            @ProfileId Long profileId,
            @PathVariable String key,
            @RequestParam(defaultValue = "0") int db) {
        return ApiResponse.success(stringService.get(profileId, db, key));
    }

    @PostMapping
    public ApiResponse<Void> set(
            @ProfileId Long profileId,
            @RequestParam(defaultValue = "0") int db,
            @Valid @RequestBody StringSetDTO dto) {
        stringService.set(profileId, db, dto.getKey(), dto.getValue(), dto.getTtlSeconds());
        return ApiResponse.success();
    }

    @PatchMapping("/{key}/append")
    public ApiResponse<Map<String, Long>> append(
            @ProfileId Long profileId,
            @PathVariable String key,
            @RequestParam(defaultValue = "0") int db,
            @Valid @RequestBody StringAppendDTO dto) {
        return ApiResponse.success(Map.of("length", stringService.append(profileId, db, key, dto.getValue())));
    }

    @DeleteMapping("/{key}")
    public ApiResponse<Void> delete(
            @ProfileId Long profileId,
            @PathVariable String key,
            @RequestParam(defaultValue = "0") int db) {
        stringService.delete(profileId, db, key);
        return ApiResponse.success();
    }
}
