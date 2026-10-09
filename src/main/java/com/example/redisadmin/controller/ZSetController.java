package com.example.redisadmin.controller;

import com.example.redisadmin.model.dto.ZSetMemberDTO;
import com.example.redisadmin.model.dto.ZSetScoreDTO;
import com.example.redisadmin.model.vo.ApiResponse;
import com.example.redisadmin.model.vo.ZSetRangeVO;
import com.example.redisadmin.model.vo.ZSetRankVO;
import com.example.redisadmin.service.ZSetService;
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
 * ZSet 端点 {@code /api/c/{id}/zsets/{key}}。
 * <p>{@code total} 用 ZCARD 真值；{@code pageSize} 上限 100。</p>
 */
@RestController
@RequestMapping("/api/c/{id}/zsets/{key}")
public class ZSetController {

    private final ZSetService zSetService;

    public ZSetController(ZSetService zSetService) {
        this.zSetService = zSetService;
    }

    /**
     * 分页读取：给 {@code min} 或 {@code max} 走分数区间 + LIMIT，
     * 否则走 {@code ZRANGE} 索引分页。
     */
    @GetMapping
    public ApiResponse<ZSetRangeVO> getRange(
            @ProfileId Long profileId,
            @PathVariable String key,
            @RequestParam(defaultValue = "0") int db,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "ASC") String order,
            @RequestParam(required = false) Double min,
            @RequestParam(required = false) Double max) {
        return ApiResponse.success(zSetService.getRange(profileId, db, key, page, pageSize, order, min, max));
    }

    @PostMapping("/members")
    public ApiResponse<Map<String, Long>> addMember(
            @ProfileId Long profileId,
            @PathVariable String key,
            @RequestParam(defaultValue = "0") int db,
            @Valid @RequestBody ZSetMemberDTO dto) {
        return ApiResponse.success(Map.of("added",
                zSetService.addMember(profileId, db, key, dto.getMember(), dto.getScore(), dto.getTtlSeconds())));
    }

    /**
     * 覆盖式设分（XX：成员不存在则不创建）。
     */
    @PatchMapping("/members/{member}/score")
    public ApiResponse<Map<String, Long>> updateScore(
            @ProfileId Long profileId,
            @PathVariable String key,
            @PathVariable String member,
            @RequestParam(defaultValue = "0") int db,
            @Valid @RequestBody ZSetScoreDTO dto) {
        return ApiResponse.success(Map.of("updated", zSetService.updateScore(profileId, db, key, member, dto.getScore())));
    }

    /**
     * ZINCRBY：幂等累加。
     */
    @PatchMapping("/members/{member}/score/incr")
    public ApiResponse<Map<String, Double>> incrementScore(
            @ProfileId Long profileId,
            @PathVariable String key,
            @PathVariable String member,
            @RequestParam(defaultValue = "0") int db,
            @Valid @RequestBody ZSetScoreDTO dto) {
        return ApiResponse.success(Map.of("score",
                zSetService.incrementScore(profileId, db, key, member, dto.getScore())));
    }

    @DeleteMapping("/members/{member}")
    public ApiResponse<Map<String, Long>> removeMember(
            @ProfileId Long profileId,
            @PathVariable String key,
            @PathVariable String member,
            @RequestParam(defaultValue = "0") int db) {
        return ApiResponse.success(Map.of("removed", zSetService.removeMember(profileId, db, key, member)));
    }

    @GetMapping("/members/{member}/rank")
    public ApiResponse<ZSetRankVO> getRank(
            @ProfileId Long profileId,
            @PathVariable String key,
            @PathVariable String member,
            @RequestParam(defaultValue = "0") int db,
            @RequestParam(defaultValue = "ASC") String order) {
        return ApiResponse.success(zSetService.getRank(profileId, db, key, member, order));
    }
}
