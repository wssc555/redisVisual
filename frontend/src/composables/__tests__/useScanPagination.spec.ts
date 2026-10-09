import {describe, expect, it} from 'vitest'
import {initialCursor, isExhausted, useScanPagination} from '../useScanPagination'

/**
 * SCAN 双游标分页断言。
 *
 * 后端有两种游标形态，交互一致但耗尽判据不同：
 * - string（K1 SCAN）：有 `exhausted` 字段；集群为 `host|port|nodeCursor` 三段式；
 * - number（H1 HSCAN / E1 SSCAN）：**无 exhausted 字段**，以 `nextCursor === 0` 判耗尽。
 */

describe('初始游标与耗尽判据', () => {
    it('初始游标：string 为 "0"，number 为 0', () => {
        expect(initialCursor('string')).toBe('0')
        expect(initialCursor('number')).toBe(0)
    })

    it('string 模式只看 exhausted 字段（不解析游标内容）', () => {
        expect(isExhausted('string', '123', true)).toBe(true)
        expect(isExhausted('string', '123', false)).toBe(false)
        expect(isExhausted('string', '0', false)).toBe(false)
    })

    it('string 模式：集群三段式游标原样透传，不因内容判耗尽', () => {
        // 'node-1:6379|0' 里含有 | 和 :，前端一律不解析
        expect(isExhausted('string', 'node-1:6379|0', false)).toBe(false)
    })

    it('number 模式以 nextCursor === 0 判耗尽，忽略 exhausted', () => {
        expect(isExhausted('number', 0)).toBe(true)
        expect(isExhausted('number', '0' as never)).toBe(true)
        expect(isExhausted('number', 42)).toBe(false)
        // 即使误传 exhausted=true，number 模式也不采信（后端不返回该字段）
        expect(isExhausted('number', 42, true)).toBe(false)
    })
})

describe('useScanPagination', () => {
    it('string 模式：加载更多逐页累积，exhausted 后停', async () => {
        const pages = [
            {items: ['a', 'b'], nextCursor: '10', exhausted: false},
            {items: ['c'], nextCursor: '20', exhausted: false},
            {items: ['d'], nextCursor: '0', exhausted: true},
        ]
        let i = 0
        const p = useScanPagination<string, string>({
            mode: 'string',
            fetchPage: async () => pages[i++],
        })

        await p.loadMore()
        expect(p.items.value).toEqual(['a', 'b'])
        expect(p.hasMore.value).toBe(true)

        await p.loadMore()
        expect(p.items.value).toEqual(['a', 'b', 'c'])

        await p.loadMore()
        expect(p.items.value).toEqual(['a', 'b', 'c', 'd'])
        expect(p.hasMore.value).toBe(false)

        // 耗尽后再点加载更多不应发请求
        const before = i
        await p.loadMore()
        expect(i).toBe(before)
    })

    it('number 模式：nextCursor===0 判耗尽', async () => {
        const pages = [
            {items: ['f1'], nextCursor: 7},
            {items: ['f2'], nextCursor: 0},
        ]
        let i = 0
        const p = useScanPagination<string, number>({
            mode: 'number',
            fetchPage: async () => pages[i++],
        })

        await p.loadMore()
        expect(p.hasMore.value).toBe(true)
        await p.loadMore()
        expect(p.items.value).toEqual(['f1', 'f2'])
        expect(p.hasMore.value).toBe(false)
    })

    it('reset 清空条目并回到首屏游标（match/db 变化时调用）', async () => {
        const pages = [
            {items: ['a'], nextCursor: '10', exhausted: false},
            {items: ['b'], nextCursor: '20', exhausted: false},
            {items: ['x'], nextCursor: '0', exhausted: true},
        ]
        let i = 0
        const cursors: string[] = []
        const p = useScanPagination<string, string>({
            mode: 'string',
            fetchPage: async (cursor) => {
                cursors.push(String(cursor))
                return pages[i++]
            },
        })

        await p.loadMore()
        await p.loadMore()
        expect(p.items.value).toHaveLength(2)

        await p.reset()
        expect(p.items.value).toHaveLength(1)
        expect(p.hasMore.value).toBe(false)
        // 游标序列：0 → 10（两次 loadMore），reset 后回到 0 重新拉首屏
        expect(cursors).toEqual(['0', '10', '0'])
    })

    it('并发 loadMore 被 loading 标志拦住，不会用同一游标重复请求', async () => {
        let calls = 0
        let resolvePage: (v: unknown) => void = () => {
        }
        const p = useScanPagination<string, string>({
            mode: 'string',
            fetchPage: () => {
                calls++
                return new Promise((resolve) => {
                    resolvePage = resolve
                }) as never
            },
        })

        const first = p.loadMore()
        const second = p.loadMore() // 并发第二次
        resolvePage({items: ['a'], nextCursor: '0', exhausted: true})
        await Promise.all([first, second])
        expect(calls).toBe(1)
    })

    it('onLoaded 在每页后收到累计条目', async () => {
        const seen: string[][] = []
        const pages = [
            {items: ['a'], nextCursor: '1', exhausted: false},
            {items: ['b'], nextCursor: '0', exhausted: true},
        ]
        let i = 0
        const p = useScanPagination<string, string>({
            mode: 'string',
            fetchPage: async () => pages[i++],
            onLoaded: (all) => seen.push([...all]),
        })
        await p.loadMore()
        await p.loadMore()
        expect(seen).toEqual([['a'], ['a', 'b']])
    })

    it('reset 使 in-flight 的旧响应过期：旧数据不会追加进新列表（竞态守卫）', async () => {
        // 旧请求（db A）挂起不放行；reset（切到 db B）先完成；随后旧请求才返回
        let releaseOld: (v: unknown) => void = () => {
        }
        let calls = 0
        const p = useScanPagination<string, string>({
            mode: 'string',
            fetchPage: () => {
                calls++
                if (calls === 1) {
                    // 第一次请求：挂起，模拟慢响应
                    return new Promise((resolve) => {
                        releaseOld = resolve
                    }) as never
                }
                // reset 发起的新请求：立即返回 db B 的数据
                return Promise.resolve({items: ['B1'], nextCursor: '0', exhausted: true}) as never
            },
        })

        const stale = p.loadMore() // db A 的旧请求在途
        await p.reset() // 切换语境：清空 + 发起新请求（已完成）
        expect(p.items.value).toEqual(['B1'])

        // 旧响应此刻才到达 —— 必须被丢弃，不得污染新列表
        releaseOld({items: ['A1'], nextCursor: '99', exhausted: false})
        await stale
        expect(p.items.value).toEqual(['B1'])
        // 游标也不得被旧响应回写（否则「加载更多」会带着 A 的游标去扫 B）
        expect(String(p.cursor.value)).toBe('0')
        expect(p.hasMore.value).toBe(false)
        // loading 恢复正常（旧响应不得干扰新请求的加载态）
        expect(p.loading.value).toBe(false)
    })
})