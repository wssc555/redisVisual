package com.example.redisadmin.controller;

import com.example.redisadmin.model.vo.ApiResponse;
import com.example.redisadmin.model.vo.KeySearchVO;
import com.example.redisadmin.service.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实时数据检索端点 {@code /api/search/keys}。
 *
 * <p>检索<b>当前存活</b>键空间（SCAN，不使用 KEYS）。与 {@code /api/history/keys} 职责分离：
 * 这里回答「现在哪些 key 含某关键词」，历史留痕去操作日志端点。</p>
 *
 * <p><b>两个关键词至少提供一个</b>：value 检索需逐 key 读值（Redis 无反向 value 索引），
 * 全库扫描成本不可控，故空条件直接 40001 而非默默跑满预算。</p>
 *
 * <p><b>结果可能是部分的</b>：{@code exhausted=false} 表示撞上了扫描预算
 * （{@code search.max-keys-per-request}）或命中数上限，前端应明示「部分结果」并建议收窄条件。
 * 另：扫描期间键空间可能变化，结果不是一致性快照。</p>
 *
 * <p>注意 {@code profileId} 是<b>查询参数</b>而非路径段（与 {@code /api/history/keys} 同构）：
 * 本端点不挂在 {@code /api/c/{id}} 下，故<b>不能</b>用 {@code @ProfileId}——
 * 该注解的解析器从 {@code {id}} 路径变量取值，此处无此段会恒抛 40001。</p>
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * 按 key / value 关键词检索。
     *
     * @param key   key 关键词：走 Redis 原生 {@code SCAN MATCH}（glob，<b>大小写敏感</b>）
     * @param value value 关键词：逐 key 读值做包含匹配（<b>大小写不敏感</b>），
     *              容器类型每 key 只检查前 {@code search.max-elements-per-key} 个元素，
     *              String 只匹配前 1MB
     * @param limit 最多返回条数，上限 {@code search.max-page-size}
     */
    @GetMapping("/keys")
    public ApiResponse<KeySearchVO> searchKeys(
            @RequestParam Long profileId,
            @RequestParam(defaultValue = "0") int db,
            @RequestParam(required = false) String key,
            @RequestParam(required = false) String value,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(searchService.search(profileId, db, key, value, limit));
    }
}