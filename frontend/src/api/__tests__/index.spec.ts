import {describe, expect, it} from 'vitest'
import axios from 'axios'
import {ApiError, applyEnvelopeInterceptors, c, encodeSegment, ErrorCode, HISTORY_OPERATIONS,} from '../index'

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
     * 这条纪律来自一次评审教训：此前的测试把拦截器逻辑**逐行复刻**进测试文件，
     * 改坏真源码时照样全绿 —— 复刻实现的测试只能测到测试文件自己。
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