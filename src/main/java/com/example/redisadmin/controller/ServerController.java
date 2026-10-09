package com.example.redisadmin.controller;

import com.example.redisadmin.model.vo.ApiResponse;
import com.example.redisadmin.model.vo.ClientInfoVO;
import com.example.redisadmin.model.vo.InfoVO;
import com.example.redisadmin.model.vo.ServerOverviewVO;
import com.example.redisadmin.model.vo.SlowLogVO;
import com.example.redisadmin.service.ServerService;
import com.example.redisadmin.web.ProfileId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 服务器监控端点 {@code /api/c/{id}/server}（<b>全部只读</b>）。
 * <p>不提供 CONFIG SET / FLUSHDB / FLUSHALL / KEYS / SHUTDOWN
 * —— 高危写操作必须走 Redis 自有运维通道（设计 §8）。</p>
 */
@RestController
@RequestMapping("/api/c/{id}/server")
public class ServerController {

    private final ServerService serverService;

    public ServerController(ServerService serverService) {
        this.serverService = serverService;
    }

    /**
     * INFO 分节；集群可传 {@code node=host:port}，缺省取首个 master。
     */
    @GetMapping("/info")
    public ApiResponse<InfoVO> getInfo(
            @ProfileId Long profileId,
            @RequestParam(defaultValue = "default") String section,
            @RequestParam(required = false) String node) {
        return ApiResponse.success(serverService.getInfo(profileId, section, node));
    }

    @GetMapping("/clients")
    public ApiResponse<List<ClientInfoVO>> getClients(@ProfileId Long profileId) {
        return ApiResponse.success(serverService.getClients(profileId));
    }

    @GetMapping("/slowlog")
    public ApiResponse<List<SlowLogVO>> getSlowLog(
            @ProfileId Long profileId,
            @RequestParam(defaultValue = "10") int count) {
        return ApiResponse.success(serverService.getSlowLog(profileId, count));
    }

    /** 集群为各 master DBSIZE 之和。 */
    @GetMapping("/dbsize")
    public ApiResponse<Map<String, Long>> getDbSize(
            @ProfileId Long profileId,
            @RequestParam(defaultValue = "0") int db) {
        return ApiResponse.success(Map.of("dbsize", serverService.getDbSize(profileId, db)));
    }

    @GetMapping("/overview")
    public ApiResponse<ServerOverviewVO> getOverview(
            @ProfileId Long profileId,
            @RequestParam(defaultValue = "0") int db) {
        return ApiResponse.success(serverService.getOverview(profileId, db));
    }
}
