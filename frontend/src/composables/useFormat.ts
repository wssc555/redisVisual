/**
 * 展示层格式化工具。
 * 后端返回原始字节数 / 微秒 / ISO 时间，统一在此转换为人类可读文本。
 */

/** 字节数 → B / KB / MB / GB / TB */
export const formatBytes = (bytes?: number | null): string => {
    if (bytes === null || bytes === undefined || Number.isNaN(bytes)) return '-'
    if (bytes < 1024) return bytes + ' B'
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(2) + ' KB'
    if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(2) + ' MB'
    if (bytes < 1024 * 1024 * 1024 * 1024)
        return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB'
    return (bytes / 1024 / 1024 / 1024 / 1024).toFixed(2) + ' TB'
}

/** 大数字千分位，用于 totalKeys / clients 等 */
export const formatNumber = (n?: number | null): string => {
    if (n === null || n === undefined || Number.isNaN(n)) return '-'
    return n.toLocaleString()
}

/**
 * 百分比。
 *
 * ⚠️ **null 与 0 语义不同，务必区分**：
 * - `null` = 尚无采样（如后端启动 10s 内 cpuUsagePercent）→ 显示 `—`
 * - `0` = 真实零值（如离线实例的 cpuUsagePercent=0.0）→ 显示 `0.00%`
 *
 * 这个区分来自后端 InstanceMetricsVO 的实际行为，混淆会让用户误判实例状态。
 */
export const formatPercent = (
    value?: number | null,
    digits = 2,
    placeholder = '—',
): string => {
    if (value === null || value === undefined || Number.isNaN(value)) return placeholder
    return `${value.toFixed(digits)}%`
}

/** 微秒 → 人类可读（慢查询日志 durationUs 用） */
export const formatMicros = (us?: number | null): string => {
    if (us === null || us === undefined || Number.isNaN(us)) return '-'
    if (us < 1000) return `${us} μs`
    if (us < 1000 * 1000) return `${(us / 1000).toFixed(2)} ms`
    return `${(us / 1000 / 1000).toFixed(2)} s`
}

/** ISO-8601 本地时间字符串 → 本地可读时间；空值返回 '-' */
export const formatDateTime = (iso?: string | null): string => {
    if (!iso) return '-'
    const d = new Date(iso)
    if (Number.isNaN(d.getTime())) return String(iso)
    return d.toLocaleString()
}

/**
 * ISO-8601 **本地时间**（无时区后缀）→ `Date`。
 *
 * 后端 HistoryController 用 `@DateTimeFormat(ISO.DATE_TIME)` 收 `2026-10-06T00:00:00`
 * 这种**不带时区**的本地时间串。直接 `new Date(str)` 会被引擎按 UTC 解析，
 * 在东八区整体偏移 8 小时（日期可能退到前一天），导致时间范围筛选错位。
 * 故按年月日时分秒**本地分量**构造。
 */
export const parseLocalDateTime = (value: string): Date | null => {
    const m = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?/.exec(value.trim())
    if (!m) return null
    return new Date(
        Number(m[1]),
        Number(m[2]) - 1,
        Number(m[3]),
        Number(m[4]),
        Number(m[5]),
        m[6] ? Number(m[6]) : 0,
    )
}

/** Date → 后端要求的 ISO-8601 本地时间串（`YYYY-MM-DDTHH:mm:ss`，无时区后缀） */
export const toLocalIsoString = (d: Date): string => {
    const p = (n: number) => String(n).padStart(2, '0')
    return (
        `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}` +
        `T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
    )
}

/** 空值占位：null / undefined / 空串统一显示为 '-' */
export const orDash = (v?: string | number | null): string => {
    if (v === null || v === undefined || v === '') return '-'
    return String(v)
}

/** 组合式导出，便于组件按需解构 */
export const useFormat = () => ({
    formatBytes,
    formatNumber,
    formatPercent,
    formatMicros,
    formatDateTime,
    orDash,
})