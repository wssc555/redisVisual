import {afterEach, describe, expect, it, vi} from 'vitest'
import axios from 'axios'
import {
    api,
    ApiError,
    applyEnvelopeInterceptors,
    c,
    encodeSegment,
    ErrorCode,
    HISTORY_OPERATIONS,
    initApiBaseUrl,
} from '../index'

/**
 * api 层契约断言（node 环境，无需挂载组件）。
 *
 * 重点是**恒 200 信封**与 **encodeSegment 的 `/` 转义** ——
 * 这两处是本项目与参照项目 kafkaVisual5 差异最大、也最容易回归的地方。
 */

describe('encodeSegment', () => {
    it('把 / 转成 %2F（encodeURIComponent 不会，必须额外替换）', () => {
        expect(encodeSegment('user/1')).toBe('user%2F1')
        expect(encodeSegment('a/b/c')).toBe('a%2Fb%2Fc')
        expect(encodeSegment('/')).toBe('%2F')
    })

    it('转义 % ? # 与空格', () => {
        expect(encodeSegment('100%')).toBe('100%25')
        expect(encodeSegment('a?b')).toBe('a%3Fb')
        expect(encodeSegment('a#b')).toBe('a%23b')
        expect(encodeSegment('a b')).toBe('a%20b')
    })

    it('emoji 按 UTF-8 百分号编码，不抛异常', () => {
        expect(encodeSegment('user:😀')).toBe('user%3A%F0%9F%98%80')
    })

    it('数字 id 正常编码', () => {
        expect(encodeSegment(42)).toBe('42')
    })

    it('含 / 且含 % 的组合不会二次编码', () => {
        expect(encodeSegment('a/b%c')).toBe('a%2Fb%25c')
    })
})

describe('c() 路径拼装', () => {
    it('拼出 /c/{id} 前缀', () => {
        expect(c(1, '/keys')).toBe('/c/1/keys')
    })

    it('与 encodeSegment 组合：含 / 的 key 落在单段内', () => {
        expect(c(1, `/keys/${encodeSegment('user/1')}`)).toBe('/c/1/keys/user%2F1')
    })
})

describe('恒 200 信封拦截器', () => {
    /**
     * 驱动**真实拦截器**：测试实例安装的是生产代码导出的 `applyEnvelopeInterceptors`
     * （与真实 `api` 单例同源），仅把 adapter 换成可编排的 stub。
     *
     * 纪律：不把拦截器逻辑**复刻**进测试文件 —— 复刻实现的测试只能测到测试文件自己，
     * 改坏真源码时照样全绿。
     */
    const buildApi = (handler: (config: unknown) => Promise<unknown>) => {
        const inst = axios.create({baseURL: '/api', timeout: 1500})
        applyEnvelopeInterceptors(inst)
        inst.defaults.adapter = handler as any
        return inst
    }

    it('code=0 时透传整个 body（调用方拿 {code,msg,data}）', async () => {
        const inst = buildApi(async () => ({
            data: {code: 0, msg: 'ok', data: {id: 7, name: 'local'}},
            status: 200,
            statusText: 'OK',
            headers: {},
            config: {},
        }))
        const r: any = await inst.get('/profiles')
        expect(r).toEqual({code: 0, msg: 'ok', data: {id: 7, name: 'local'}})
    })

    it('code≠0 时 reject 为 ApiError —— 即使 HTTP 状态是 200', async () => {
        const inst = buildApi(async () => ({
            data: {code: 40402, msg: 'Key 不存在: user/1'},
            status: 200,
            statusText: 'OK',
            headers: {},
            config: {},
        }))
        await expect(inst.get('/whatever')).rejects.toBeInstanceOf(ApiError)
        try {
            await inst.get('/whatever')
            throw new Error('本应 reject')
        } catch (e) {
            expect(e).toBeInstanceOf(ApiError)
            expect((e as ApiError).code).toBe(ErrorCode.KEY_NOT_FOUND)
            // 关键：HTTP 状态仍是 200，错误只由响应体 code 表达
            expect((e as ApiError).status).toBe(200)
            expect((e as ApiError).message).toBe('Key 不存在: user/1')
        }
    })

    it('无返回值写操作：data 整体省略也不误判为错误', async () => {
        const inst = buildApi(async () => ({
            data: {code: 0, msg: 'ok'}, // 无 data 键
            status: 200,
            statusText: 'OK',
            headers: {},
            config: {},
        }))
        const r: any = await inst.delete('/profiles/1')
        expect(r.code).toBe(0)
        expect(r.data).toBeUndefined()
    })

    it('非信封响应原样透传，不误判为错误', async () => {
        const inst = buildApi(async () => ({
            data: 'plain text',
            status: 200,
            statusText: 'OK',
            headers: {},
            config: {},
        }))
        expect(await inst.get('/x')).toBe('plain text')
    })

    it('网络层失败（无响应体）也归一化为 ApiError', async () => {
        const inst = buildApi(async () => {
            throw Object.assign(new Error('Network Error'), {config: {}})
        })
        try {
            await inst.get('/x')
            throw new Error('本应 reject')
        } catch (e) {
            expect(e).toBeInstanceOf(ApiError)
            expect((e as ApiError).message).toBe('Network Error')
        }
    })
})

describe('错误码常量与后端 ErrorCode 同号同义', () => {
    it('5 位码齐全', () => {
        expect(ErrorCode).toEqual({
            VALIDATION_ERROR: 40001,
            PROFILE_NOT_FOUND: 40401,
            KEY_NOT_FOUND: 40402,
            TYPE_MISMATCH: 40902,
            REDIS_UNAVAILABLE: 50302,
            REDIS_COMMAND_FAILED: 50001,
            SYSTEM_ERROR: 50000,
        })
    })
})

describe('操作日志枚举', () => {
    it('18 个操作，与后端记录的 Redis 命令语义一致', () => {
        expect(HISTORY_OPERATIONS).toHaveLength(18)
        expect(HISTORY_OPERATIONS).toContain('SET')
        expect(HISTORY_OPERATIONS).toContain('DEL')
        expect(HISTORY_OPERATIONS).toContain('ZINCRBY')
    })
})

/**
 * 桌面壳握手（`globalThis.redisviz`）= Electron preload 暴露的三通道。
 *
 * 它是「桌面态仪表盘空白」的唯一根因所在，值得用 3 条用例钉死：
 * 无桥 → '/api'；有桥 → 改指 127.0.0.1:{port}；超时 → 回传 startup_errors。
 */
describe('initApiBaseUrl 桌面桥探测', () => {
    const bridgeKey = 'redisviz'

    const setBridge = (value: unknown): void => {
        ;(globalThis as Record<string, unknown>)[bridgeKey] = value
    }

    afterEach(() => {
        delete (globalThis as Record<string, unknown>)[bridgeKey]
        api.defaults.baseURL = '/api'
    })

    it('无桥（浏览器 / vite dev）保持 /api，不返回错误', async () => {
        await expect(initApiBaseUrl()).resolves.toEqual([])
        expect(api.defaults.baseURL).toBe('/api')
    })

    it('桥返回端口后把 baseURL 指向 sidecar', async () => {
        setBridge({
            backendPort: async () => 45678,
            startupErrors: async () => [],
        })
        await expect(initApiBaseUrl()).resolves.toEqual([])
        expect(api.defaults.baseURL).toBe('http://127.0.0.1:45678/api')
    })

    it('端口非法值不算握手成功（0 / 超范围 / null 全部继续轮询）', async () => {
        let attempt = 0
        setBridge({
            backendPort: async () => {
                attempt += 1
                // 前两次给非法值，第三次给合法端口 —— 证明判据是「整数且在 1..65535」
                return attempt === 1 ? 0 : attempt === 2 ? 70000 : 12345
            },
            startupErrors: async () => [],
        })
        await expect(initApiBaseUrl()).resolves.toEqual([])
        expect(attempt).toBe(3)
        expect(api.defaults.baseURL).toBe('http://127.0.0.1:12345/api')
    })

    it('60s 超时后回传壳侧 startup_errors（fake timers 免等真实 60s）', async () => {
        vi.useFakeTimers()
        try {
            setBridge({
                backendPort: async () => null,
                startupErrors: async () => ['后端资源缺失：安装包不完整'],
            })
            const pending = initApiBaseUrl()
            // 120 次 × 500ms = 60s，advanceTimersByTimeAsync 会逐个推进定时器并冲刷微任务
            await vi.advanceTimersByTimeAsync(120 * 500)
            await expect(pending).resolves.toEqual(['后端资源缺失：安装包不完整'])
            expect(api.defaults.baseURL).toBe('/api')
        } finally {
            vi.useRealTimers()
        }
    })

    it('壳侧未记录错误时兜底为一条非空提示文案', async () => {
        vi.useFakeTimers()
        try {
            setBridge({
                backendPort: async () => null,
                startupErrors: async () => [],
            })
            const pending = initApiBaseUrl()
            await vi.advanceTimersByTimeAsync(120 * 500)
            const errors = await pending
            expect(errors).toHaveLength(1)
            expect(errors[0].length).toBeGreaterThan(0)
        } finally {
            vi.useRealTimers()
        }
    })
})