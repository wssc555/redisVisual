import type {HistoryOperation} from '../api'

/**
 * 操作日志页的筛选草稿（HistoryFilterBar 与 HistoryLog 共享的类型）。
 * 独立成文件是因为 `<script setup>` 不能 export 类型。
 */
export interface HistoryFilters {
    /** 不传 = 全部实例（含已删实例的历史） */
    profileId?: number
    db?: number
    /** key 关键词，子串匹配大小写不敏感 */
    key?: string
    /** value 关键词，只覆盖 value_preview 预览窗口；全表扫描 */
    value?: string
    operation?: HistoryOperation | string
    /** ISO-8601 本地时间，如 2026-10-06T00:00:00（无时区后缀） */
    startTime?: string
    endTime?: string
}