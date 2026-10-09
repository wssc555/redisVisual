package com.example.redisadmin.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 实时检索结果。
 *
 * <h3>为什么没有 page / pageSize</h3>
 * SCAN 是流式遍历，<b>无稳定总数</b>，「真分页」在语义上不成立。这里给的是
 * 「最多 {@code limit} 条命中 + 本次是否扫完」的诚实契约：
 * <ul>
 *   <li>{@code exhausted=false} → 结果是<b>部分结果</b>（撞上扫描预算或命中数上限），
 *       前端应明示「部分结果」并建议收窄条件；这不是错误</li>
 *   <li>{@code exhausted=true} → 整个当前键空间已扫完，结果完整</li>
 * </ul>
 * 另：扫描期间键空间可能变化，结果<b>不是一致性快照</b>。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeySearchVO {

    private List<KeySearchHitVO> items;
    /**
     * 本次实际检查的 key 数。
     * <p>value 维度 = 逐 key 读值检查的数量（受预算封顶）；
     * key 维度（走 SCAN MATCH）= <b>服务端匹配命中的数量</b>——Redis 侧实际遍历量
     * 可能远大于此值（这正是 MATCH 的成本优势），预算不限制服务端工作量。</p>
     */
    private int scanned;
    /**
     * 键空间是否已扫完；false 表示部分结果（撞上扫描预算或命中数上限）。
     */
    private boolean exhausted;
    /**
     * 单请求扫描预算（即 {@code search.max-keys-per-request} 配置值，原样回显）。
     * 供前端在 {@code exhausted=false} 时提示用户「已扫 N/预算 M」；扫完时无业务含义。
     */
    private int budget;
}