package com.example.redisadmin.controller;

import com.example.redisadmin.model.dto.SetMembersDTO;
import com.example.redisadmin.model.vo.ApiResponse;
import com.example.redisadmin.model.vo.SetScanVO;
import com.example.redisadmin.service.SetService;
import com.example.redisadmin.web.ProfileId;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Set 端点 {@code /api/c/{id}/sets/{key}}。SSCAN 游标分页（默认 50，上限 500）。
 */
@RestController
@RequestMapping("/api/c/{id}/sets/{key}")
public class SetController {

    private final SetService setService;

    public SetController(SetService setService) {
        this.setService = setService;
    }

    /**
     * SSCAN 分页（默认 50，上限 500）。不传 {@code count} 视为无界全量请求，
     * 大 Key 将被拒绝 → 40001。
     */
    @GetMapping
    public ApiResponse<SetScanVO> scan(
            @ProfileId Long profileId,
            @PathVariable String key,
            @RequestParam(defaultValue = "0") int db,
            @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(required = false) Integer count) {
        return ApiResponse.success(setService.scan(profileId, db, key, cursor, count));
    }

    @PostMapping("/members")
    public ApiResponse<Map<String, Long>> addMembers(
            @ProfileId Long profileId,
            @PathVariable String key,
            @RequestParam(defaultValue = "0") int db,
            @Valid @RequestBody SetMembersDTO dto) {
        return ApiResponse.success(Map.of("added",
                setService.addMembers(profileId, db, key, dto.getMembers(), dto.getTtlSeconds())));
    }

    @GetMapping("/members/{member}/exists")
    public ApiResponse<Map<String, Boolean>> isMember(
            @ProfileId Long profileId,
            @PathVariable String key,
            @PathVariable String member,
            @RequestParam(defaultValue = "0") int db) {
        return ApiResponse.success(Map.of("exists", setService.isMember(profileId, db, key, member)));
    }

    @DeleteMapping("/members/{member}")
    public ApiResponse<Map<String, Long>> removeMember(
            @ProfileId Long profileId,
            @PathVariable String key,
            @PathVariable String member,
            @RequestParam(defaultValue = "0") int db) {
        return ApiResponse.success(Map.of("removed", setService.removeMember(profileId, db, key, member)));
    }
}
