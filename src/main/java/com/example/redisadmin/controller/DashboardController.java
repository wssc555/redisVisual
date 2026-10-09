package com.example.redisadmin.controller;

import com.example.redisadmin.model.vo.ApiResponse;
import com.example.redisadmin.model.vo.ClusterTopologyVO;
import com.example.redisadmin.model.vo.DashboardOverviewVO;
import com.example.redisadmin.model.vo.LoadTrendVO;
import com.example.redisadmin.model.vo.MemoryTrendVO;
import com.example.redisadmin.service.DashboardService;
import com.example.redisadmin.web.ProfileId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跨实例仪表盘 {@code /api/dashboard}。
 * <p>趋势数据来自内存环形缓冲，重启即清零（不承诺持久化）。</p>
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /**
     * 跨实例聚合（Caffeine 5s 缓存）。离线实例不拖垮整体响应。
     */
    @GetMapping("/overview")
    public ApiResponse<DashboardOverviewVO> getOverview() {
        return ApiResponse.success(dashboardService.getOverview());
    }

    /**
     * 集群/哨兵节点拓扑；实例不存在 → 40401，单机模式 → 40001。
     */
    @GetMapping("/topology/{id}")
    public ApiResponse<ClusterTopologyVO> getTopology(@ProfileId Long profileId) {
        return ApiResponse.success(dashboardService.getTopology(profileId));
    }

    @GetMapping("/memory-trend")
    public ApiResponse<MemoryTrendVO> getMemoryTrend(@RequestParam(defaultValue = "300") int seconds) {
        return ApiResponse.success(dashboardService.getMemoryTrend(seconds));
    }

    @GetMapping("/load-trend")
    public ApiResponse<LoadTrendVO> getLoadTrend(@RequestParam(defaultValue = "300") int seconds) {
        return ApiResponse.success(dashboardService.getLoadTrend(seconds));
    }
}
