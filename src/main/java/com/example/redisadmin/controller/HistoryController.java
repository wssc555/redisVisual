package com.example.redisadmin.controller;

import com.example.redisadmin.model.vo.ApiResponse;
import com.example.redisadmin.model.vo.KeyHistoryPageVO;
import com.example.redisadmin.service.KeyHistoryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 操作日志查询端点 {@code /api/history/keys}。
 *
 * <p><b>覆盖边界（务必向使用者明示）</b>：只记录<b>经本平台</b>执行的写/删操作。
 * 外部客户端（redis-cli、业务应用）的变更与 Redis 端被动 TTL 过期<b>不在其中</b>——
 * Redis 原生不提供 key 创建/删除时间元数据，无可回溯途径。日志不是全量事实。</p>
 *
 * <p>{@code operation} 直接使用 Redis 命令语义（{@code SET} / {@code DEL} / {@code RENAME} /
 * {@code HSET} / {@code LPUSH} / {@code ZADD} ...），筛 {@code DEL} 即得删除记录。</p>
 *
 * <p>时间格式：ISO-8601 本地时间，如 {@code 2026-10-06T00:00:00}。</p>
 */
@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final KeyHistoryService historyService;

    public HistoryController(KeyHistoryService historyService) {
        this.historyService = historyService;
    }

    /**
     * 组合条件分页查询，全部参数可选、AND 组合。
     * <p>key / value 为大小写不敏感子串匹配（{@code %kw%}，通配符已转义）；
     * 按操作时间倒序。value 检索只覆盖 {@code history.preview-bytes} 字节的预览窗口。</p>
     */
    @GetMapping("/keys")
    public ApiResponse<KeyHistoryPageVO> queryKeys(
            @RequestParam(required = false) Long profileId,
            @RequestParam(required = false) Integer db,
            @RequestParam(required = false) String key,
            @RequestParam(required = false) String value,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(historyService.query(
                profileId, db, key, value, operation, startTime, endTime, page, pageSize));
    }
}