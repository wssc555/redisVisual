import {describe, expect, it} from 'vitest'
import {
    buildProfilePayload,
    buildValidatePayload,
    defaultProfileForm,
    formFromProfile,
    isCleared,
    toggleCleared,
    validateProfileForm,
} from '../profileForm'
import type {ProfileVO} from '../../api'

/**
 * 连接配置表单的纯逻辑断言（node 环境直接跑，无 Vue / DOM 依赖）。
 *
 * **密码三态是本模块存在的唯一理由**：把"没动密码框"错传成空串
 * 等于**清空了用户的密码**。下面的用例把这三种情况逐一钉死。
 */

const baseForm = () => {
    const f = defaultProfileForm()
    f.name = 'local'
    f.mode = 'STANDALONE'
    f.host = '127.0.0.1'
    f.port = 6379
    return f
}

describe('密码三态', () => {
    it('未填写 → payload 里**没有 password 键**（保持库中原值）', () => {
        const f = baseForm()
        f.password = ''
        const p = buildProfilePayload(f) as unknown as Record<string, unknown>
        expect('password' in p).toBe(false)
        expect(Object.prototype.hasOwnProperty.call(p, 'password')).toBe(false)
    })

    it('标记清空 → payload 里 password 为空串（显式清空）', () => {
        const f = baseForm()
        f.password = ''
        toggleCleared(f, 'password')
        const p = buildProfilePayload(f) as unknown as Record<string, unknown>
        expect(p.password).toBe('')
    })

    it('填写新值 → payload 里 password 为新值（替换）', () => {
        const f = baseForm()
        f.password = 'new-secret'
        const p = buildProfilePayload(f) as unknown as Record<string, unknown>
        expect(p.password).toBe('new-secret')
    })

    it('取消清空后回到"省略键"，不会残留空串', () => {
        const f = baseForm()
        f.password = ''
        toggleCleared(f, 'password')
        expect(isCleared(f, 'password')).toBe(true)
        toggleCleared(f, 'password')
        expect(isCleared(f, 'password')).toBe(false)
        const p = buildProfilePayload(f) as unknown as Record<string, unknown>
        expect('password' in p).toBe(false)
    })

    it('哨兵密码仅在哨兵模式下提交（切模式不该顺手清掉另一种凭据）', () => {
        const f = baseForm()
        f.mode = 'SENTINEL'
        f.sentinels = [{host: '10.0.0.1', port: 26379}]
        f.masterName = 'mymaster'
        f.password = 'data-pw'
        f.sentinelPassword = 'sentinel-pw'
        const p = buildProfilePayload(f) as unknown as Record<string, unknown>
        expect(p.password).toBe('data-pw')
        expect(p.sentinelPassword).toBe('sentinel-pw')

        // 切回单机：哨兵密码不应出现在 payload 里（但不等于清空库中值）
        f.mode = 'STANDALONE'
        const p2 = buildProfilePayload(f) as unknown as Record<string, unknown>
        expect('sentinelPassword' in p2).toBe(false)
        expect(p2.password).toBe('data-pw')
    })
})

describe('三模式条件字段', () => {
    it('单机：只提交 host/port，不带 nodes/sentinels', () => {
        const f = baseForm()
        const p = buildProfilePayload(f)
        expect(p.mode).toBe('STANDALONE')
        expect(p.host).toBe('127.0.0.1')
        expect(p.port).toBe(6379)
        expect(p.nodes).toBeUndefined()
        expect(p.sentinels).toBeUndefined()
    })

    it('集群：提交 nodes，过滤全空行', () => {
        const f = baseForm()
        f.mode = 'CLUSTER'
        f.nodes = [
            {host: '10.0.0.1', port: 6379},
            {host: '', port: 6379}, // 用户点了"添加节点"但没填 → 应被滤掉
            {host: '10.0.0.2', port: 6380},
        ]
        const p = buildProfilePayload(f)
        expect(p.nodes).toHaveLength(2)
        expect(p.host).toBeUndefined()
    })

    it('哨兵：提交 sentinels + masterName', () => {
        const f = baseForm()
        f.mode = 'SENTINEL'
        f.sentinels = [{host: '10.0.0.1', port: 26379}]
        f.masterName = 'mymaster'
        const p = buildProfilePayload(f)
        expect(p.sentinels).toHaveLength(1)
        expect(p.masterName).toBe('mymaster')
    })
})

describe('前端预检（后端 40001 的镜像）', () => {
    it('名称为空 → 报错', () => {
        const f = baseForm()
        f.name = ''
        expect(validateProfileForm(f).length).toBeGreaterThan(0)
    })

    it('单机缺 host → 报错；填齐 → 通过', () => {
        const f = baseForm()
        f.host = ''
        expect(validateProfileForm(f).some((m) => m.key.endsWith('hostRequired'))).toBe(true)
        f.host = '127.0.0.1'
        expect(validateProfileForm(f)).toEqual([])
    })

    it('端口越界 → 报错', () => {
        const f = baseForm()
        f.port = 70000
        expect(validateProfileForm(f).some((m) => m.key.endsWith('portRequired'))).toBe(true)
        f.port = 0
        expect(validateProfileForm(f).some((m) => m.key.endsWith('portRequired'))).toBe(true)
    })

    it('集群无有效节点 → 报错', () => {
        const f = baseForm()
        f.mode = 'CLUSTER'
        f.nodes = [{host: '', port: null}]
        expect(validateProfileForm(f).some((m) => m.key.endsWith('nodesRequired'))).toBe(true)
    })

    it('哨兵缺主节点名 → 报错', () => {
        const f = baseForm()
        f.mode = 'SENTINEL'
        f.sentinels = [{host: '10.0.0.1', port: 26379}]
        expect(validateProfileForm(f).some((m) => m.key.endsWith('masterRequired'))).toBe(true)
        f.masterName = 'mymaster'
        expect(validateProfileForm(f)).toEqual([])
    })

    it('密码留空**不算错误**（无认证实例是合法配置）', () => {
        const f = baseForm()
        f.password = ''
        expect(validateProfileForm(f)).toEqual([])
    })
})

describe('编辑态回显', () => {
    const profile: ProfileVO = {
        id: 1,
        name: 'local',
        mode: 'SENTINEL',
        sentinels: [{host: '10.0.0.1', port: 26379}],
        masterName: 'mymaster',
        database: 2,
        username: 'app',
        credentialPresence: 'PRESENT',
        sentinelCredentialPresence: 'PRESENT',
        profileState: 'CONNECTED',
    }

    it('秘密字段留空（后端永不回明文），clearedSecrets 初始为空', () => {
        const f = formFromProfile(profile)
        expect(f.password).toBe('')
        expect(f.sentinelPassword).toBe('')
        expect(f.clearedSecrets).toEqual([])
    })

    it('非秘密字段正常回显', () => {
        const f = formFromProfile(profile)
        expect(f.name).toBe('local')
        expect(f.mode).toBe('SENTINEL')
        expect(f.masterName).toBe('mymaster')
        expect(f.database).toBe(2)
        expect(f.username).toBe('app')
        expect(f.sentinels).toHaveLength(1)
    })

    it('编辑态回显后直接提交 → 两个密码都省略（库中密文不变）', () => {
        const f = formFromProfile(profile)
        const p = buildProfilePayload(f) as unknown as Record<string, unknown>
        expect('password' in p).toBe(false)
        expect('sentinelPassword' in p).toBe(false)
    })

    it('集群至少保留一行空输入框，避免用户看到无法添加的空列表', () => {
        const f = formFromProfile({...profile, mode: 'CLUSTER', sentinels: null})
        expect(f.mode).toBe('CLUSTER')
        expect(f.nodes.length).toBe(1)
        expect(f.nodes[0].host).toBe('')
    })
})

describe('测连请求体', () => {
    it('编辑态带 id（后端据此从库里补全省略的密码）', () => {
        const f = baseForm()
        const p = buildValidatePayload(f, true, 5)
        expect(p.id).toBe(5)
    })

    it('新建态不带 id', () => {
        const f = baseForm()
        const p = buildValidatePayload(f, false)
        expect(p.id).toBeUndefined()
    })

    it('测连体与 upsert 体一致（不额外泄露密码）', () => {
        const f = baseForm()
        f.password = 'pw'
        expect(buildValidatePayload(f, false)).toEqual(buildProfilePayload(f))
    })
})