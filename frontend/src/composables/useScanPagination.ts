import {ref, type Ref} from 'vue'

/**
 * SCAN 游标分页的统一抽象（**string / number 双游标两种模式**）。
 *
 * 后端的 HSCAN / SSCAN（游标 number、`nextCursor === 0` 判耗尽）与
 * K1 SCAN（游标 string、有 `exhausted` 字段、集群为 `host|port|nodeCursor` 三段式）
 * 语义不同但交互一致：**「加载更多」而非页码**。本 composable 把两者收敛成
 * 同一个 `loadMore()` 调用点，差异只在游标类型与耗尽判据。
 *
 * ⚠️ 集群游标**原样透传、绝不解析** —— 三段式由后端自行拆解，
 * 前端一旦 split 就等于复制了后端的路由逻辑。
 */

export type ScanCursor = string | number

/** 游标模式：string（K1）/ number（H1、E1） */
export type CursorMode = 'string' | 'number'

/** 初始游标：string 模式后端默认 "0"，number 模式默认 0 */
export const initialCursor = (mode: CursorMode): ScanCursor => (mode === 'string' ? '0' : 0)

/**
 * 判耗尽。
 *
 * - string 模式：后端回 `exhausted` 布尔（K1 有该字段）；
 * - number 模式：**无 exhausted 字段**，以 `nextCursor === 0` 判耗尽。
 */
export const isExhausted = (mode: CursorMode, nextCursor: ScanCursor, exhausted?: boolean): boolean => {
    if (mode === 'number') return Number(nextCursor) === 0
    return exhausted === true
}

export interface ScanPaginationOptions<T, C extends ScanCursor> {
    /** 游标模式 */
    mode: CursorMode
    /** 拉取一页：接收当前游标，返回该页数据与后端给的下一游标/耗尽标记 */
    fetchPage: (cursor: C) => Promise<{
        items: T[]
        nextCursor: ScanCursor
        exhausted?: boolean
    }>
    /** 首屏加载完成后的回调（用于同步总数等） */
    onLoaded?: (all: T[]) => void
}

export interface ScanPagination<T, C extends ScanCursor> {
    /** 已累积的全部条目（按扫描游标顺序，**非字母序**；可能含重复 —— SCAN 语义允许） */
    items: Ref<T[]>
    /** 当前游标 */
    cursor: Ref<C>
    /** 是否还有下一页 */
    hasMore: Ref<boolean>
    /** 是否正在拉取（防重复点击） */
    loading: Ref<boolean>
    /** 首屏加载 / 加载更多 */
    loadMore: () => Promise<void>
    /** 重置并重新拉首屏（match 变化、db 切换、key 类型切换时调用） */
    reset: () => Promise<void>
}

export const useScanPagination = <T, C extends ScanCursor = ScanCursor>(
    options: ScanPaginationOptions<T, C>,
): ScanPagination<T, C> => {
    const items = ref<T[]>([] as T[]) as Ref<T[]>
    const cursor = ref(initialCursor(options.mode)) as Ref<C>
    const hasMore = ref(true)
    const loading = ref(false)

    /**
     * 代际 token（竞态守卫）。
     *
     * `reset()` 与 in-flight 的 `loadMore()` 存在竞态：切换 db/实例/match 时旧请求
     * 尚未返回，reset 先清空列表并发起新请求，旧响应随后到达会把**上一个语境的
     * 数据追加进新列表**并回写旧游标。每次 reset 自增 generation，响应落地前
     * 比对 token，不一致的响应直接丢弃。
     */
    let generation = 0

    const loadMore = async () => {
        // 防并发：连点「加载更多」会带着同一游标重复请求，且可能造成重复累积
        if (loading.value || !hasMore.value) return
        loading.value = true
        const gen = generation
        try {
            const page = await options.fetchPage(cursor.value)
            // 本请求发出后发生过 reset → 响应已过期，丢弃（新请求会带回自己的数据）
            if (gen !== generation) return
            const batch = page.items ?? []
            // 首批替换、后续追加：reset 后 cursor 归零，语义上首批就是"首批"
            items.value = batch.length === 0 && page.nextCursor === initialCursor(options.mode)
                ? items.value
                : [...items.value, ...batch]
            cursor.value = page.nextCursor as C
            hasMore.value = !isExhausted(options.mode, page.nextCursor, page.exhausted)
            options.onLoaded?.(items.value)
        } finally {
            // 只有最新一代才能收 loading：过期响应不干扰新一代请求的加载态
            if (gen === generation) loading.value = false
        }
    }

    const reset = async () => {
        // 使所有 in-flight 请求过期 —— 它们的响应落地时会被上面的 token 比对丢弃
        generation++
        items.value = []
        cursor.value = initialCursor(options.mode) as C
        hasMore.value = true
        loading.value = false
        await loadMore()
    }

    return {items, cursor, hasMore, loading, loadMore, reset}
}