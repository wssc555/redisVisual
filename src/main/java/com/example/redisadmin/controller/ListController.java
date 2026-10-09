package com.example.redisadmin.controller;

import com.example.redisadmin.model.dto.ListPushDTO;
import com.example.redisadmin.model.dto.ListSetByIndexDTO;
import com.example.redisadmin.model.vo.ApiResponse;
import com.example.redisadmin.model.vo.ListRangeVO;
import com.example.redisadmin.service.ListService;
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

import java.util.HashMap;
import java.util.Map;

/**
 * List 端点 {@code /api/c/{id}/lists/{key}}。
 * <p>按索引删除为 {@code LSET + LREM} 两步，<b>非原子</b>。</p>
 */
@RestController
@RequestMapping("/api/c/{id}/lists/{key}")
public class ListController {

    private final ListService listService;

    public ListController(ListService listService) {
        this.listService = listService;
    }

    /**
     * LRANGE 区间读取，区间长度上限 5000（超限 → 40001）。
     */
    @GetMapping
    public ApiResponse<ListRangeVO> getRange(
            @ProfileId Long profileId,
            @PathVariable String key,
            @RequestParam(defaultValue = "0") int db,
            @RequestParam(defaultValue = "0") long start,
            @RequestParam(defaultValue = "19") long end) {
        return ApiResponse.success(listService.getRange(profileId, db, key, start, end));
    }

    @PostMapping("/lpush")
    public ApiResponse<Map<String, Long>> lpush(
            @ProfileId Long profileId,
            @PathVariable String key,
            @RequestParam(defaultValue = "0") int db,
            @Valid @RequestBody ListPushDTO dto) {
        return ApiResponse.success(Map.of("length", listService.lpush(profileId, db, key, dto.getValues(), dto.getTtlSeconds())));
    }

    @PostMapping("/rpush")
    public ApiResponse<Map<String, Long>> rpush(
            @ProfileId Long profileId,
            @PathVariable String key,
            @RequestParam(defaultValue = "0") int db,
            @Valid @RequestBody ListPushDTO dto) {
        return ApiResponse.success(Map.of("length", listService.rpush(profileId, db, key, dto.getValues(), dto.getTtlSeconds())));
    }

    @PatchMapping("/{index}")
    public ApiResponse<Void> setByIndex(
            @ProfileId Long profileId,
            @PathVariable String key,
            @PathVariable long index,
            @RequestParam(defaultValue = "0") int db,
            @Valid @RequestBody ListSetByIndexDTO dto) {
        listService.setByIndex(profileId, db, key, index, dto.getValue());
        return ApiResponse.success();
    }

    /** 非原子删除（LSET 哨兵值 + LREM），并发修改下请重拉区间。 */
    @DeleteMapping("/{index}")
    public ApiResponse<Void> deleteByIndex(
            @ProfileId Long profileId,
            @PathVariable String key,
            @PathVariable long index,
            @RequestParam(defaultValue = "0") int db) {
        listService.deleteByIndex(profileId, db, key, index);
        return ApiResponse.success();
    }

    @PostMapping("/pop")
    public ApiResponse<Map<String, String>> pop(
            @ProfileId Long profileId,
            @PathVariable String key,
            @RequestParam(defaultValue = "0") int db,
            @RequestParam(defaultValue = "LEFT") String direction) {
        String value = listService.pop(profileId, db, key, direction);
        Map<String, String> resp = new HashMap<>();
        resp.put("value", value);
        return ApiResponse.success(resp);
    }
}
