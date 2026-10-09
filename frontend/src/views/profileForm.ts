import type {
    CredentialPresence,
    DeployMode,
    ProfileNodePayload,
    ProfileUpsertPayload,
    ProfileValidatePayload,
    ProfileVO,
} from '../api'

/**
 * 连接配置表单的纯逻辑模块。
 *
 * 把「表单状态 ↔ 请求体」的映射与前端预检规则从 ProfileFormDialog.vue 里抽出来，
 * 原因有两个：
 * 1. **秘密三态**（留空保持 / 空串清空 / 新值替换）是最容易写错的地方 ——
 *    把"没动密码框"错传成空串等于**清空了用户的密码**，必须有纯函数把它钉死并可单测；
 * 2. 三模式条件字段 + 三态的组合规则塞在组件里既难读也无法在 node 环境直接跑。
 *
 * 本模块不依赖 Vue、不依赖 i18n、不依赖 DOM。用户可见文案以**翻译 key（+参数）**返回，
 * 视图层负责 t() 渲染 —— 语言包在 src/i18n/messages/profileManage.ts。
 */

/** 结构化翻译消息：key 指向 profileManage 域语言包；params 供命名插值。 */
export interface FormMessage {
    key: string
    params?: Record<string, string | number>
}

/** 表单里的一行节点（集群 nodes / 哨兵 sentinels 共用）。 */
export interface NodeRow {
    host: string
    port: number | null
}

/**
 * 表单状态。
 *
 * **秘密字段一律以「空串 = 未填写」表达**，真正的三态判定靠 {@link ProfileForm.clearedSecrets}：
 * - 空串且不在 clearedSecrets 里 → 提交时**省略键**（编辑时保持库中原值）；
 * - 空串且在 clearedSecrets 里 → 提交空串（显式清空）；
 * - 非空 → 作为新值提交。
 */
export interface ProfileForm {
    name: string
    mode: DeployMode
    /** STANDALONE */
    host: string
    port: number | null
    /** CLUSTER */
    nodes: NodeRow[]
    /** SENTINEL */
    sentinels: NodeRow[]
    masterName: string
    database: number
    username: string
    /** 三态，见类注释 */
    password: string
    /** 哨兵密码，与数据密码相互独立 */
    sentinelPassword: string
    /** 被显式清空的秘密字段名 */
    clearedSecrets: Array<'password' | 'sentinelPassword'>
}

export const SECRET_FIELDS = ['password', 'sentinelPassword'] as const
export type SecretField = (typeof SECRET_FIELDS)[number]

/** 部署模式选项（文案以 key 给出，视图层经 t() 渲染）。 */
export const MODE_OPTIONS: Array<{ value: DeployMode; labelKey: string }> = [
    {value: 'STANDALONE', labelKey: 'profileManage.mode.STANDALONE'},
    {value: 'CLUSTER', labelKey: 'profileManage.mode.CLUSTER'},
    {value: 'SENTINEL', labelKey: 'profileManage.mode.SENTINEL'},
]

export function defaultProfileForm(): ProfileForm {
    return {
        name: '',
        mode: 'STANDALONE',
        host: '',
        port: 6379,
        nodes: [{host: '', port: null}],
        sentinels: [{host: '', port: null}],
        masterName: '',
        database: 0,
        username: '',
        password: '',
        sentinelPassword: '',
        clearedSecrets: [],
    }
}

/**
 * 由列表项回显表单。
 *
 * 秘密材料一律留空（后端只回 credentialPresence 存在性标记、永不回明文）——
 * 用户不改就不提交，改了才写新值。clearedSecrets 初始为空：
 * 不碰密码框 = 保持原值。
 */
export function formFromProfile(p: ProfileVO): ProfileForm {
    const form = defaultProfileForm()
    form.name = p.name
    form.mode = p.mode
    form.host = p.host ?? ''
    form.port = p.port ?? null
    form.nodes = (p.nodes ?? []).map((n) => ({host: n.host, port: n.port}))
    form.sentinels = (p.sentinels ?? []).map((n) => ({host: n.host, port: n.port}))
    form.masterName = p.masterName ?? ''
    form.database = p.database ?? 0
    form.username = p.username ?? ''
    // 集群/哨兵至少保留一行空的输入框，避免用户一进来就看到空列表不知怎么加
    if (p.mode === 'CLUSTER' && form.nodes.length === 0) form.nodes = [{host: '', port: null}]
    if (p.mode === 'SENTINEL' && form.sentinels.length === 0) {
        form.sentinels = [{host: '', port: null}]
    }
    return form
}

// ---------------------------------------------------------------
// 秘密字段辅助（三态判定）
// ---------------------------------------------------------------

/** 该秘密字段是否已被标记为"显式清空"。 */
export const isCleared = (form: ProfileForm, field: SecretField): boolean =>
    form.clearedSecrets.includes(field)

/**
 * 切换秘密字段的"清除"标记。
 *
 * 语义：标记为清除时输入框被清空并**不允许再输入**（要填新值就先取消清除），
 * 这样"留空 = 不修改"与"留空 = 清空"两个状态在 UI 上不会互相打架。
 */
export const toggleCleared = (form: ProfileForm, field: SecretField): void => {
    if (isCleared(form, field)) {
        form.clearedSecrets = form.clearedSecrets.filter((f) => f !== field)
    } else {
        form.clearedSecrets = [...form.clearedSecrets, field]
        form[field] = ''
    }
}

/**
 * 编辑态下秘密输入框的占位文案（翻译 key）。
 * @param configured 后端回的 credentialPresence === 'PRESENT'
 */
export const secretPlaceholder = (configured: boolean): string =>
    configured ? 'profileManage.form.secretKeep' : 'profileManage.form.secretNotConfigured'

/** 凭据存在性 → 展示文案（翻译 key）。 */
export const presenceTextKey = (p?: CredentialPresence | null): string =>
    p === 'PRESENT' ? 'profileManage.auth.present' : 'profileManage.auth.none'

// ---------------------------------------------------------------
// 前端预检（后端 40001 的镜像，只做"说得出原因"的那部分）
// ---------------------------------------------------------------

const isValidPort = (port: number | null): boolean =>
    port !== null && Number.isInteger(port) && port > 0 && port < 65536

/** 节点行列表 → payload 形态，过滤掉完全空的行（用户点了"添加节点"但没填）。 */
export const collectNodes = (rows: NodeRow[]): ProfileNodePayload[] =>
    rows
        .filter((r) => r.host.trim() !== '')
        .map((r) => ({host: r.host.trim(), port: r.port as number}))

/**
 * 整表校验；返回结构化消息数组（空数组 = 通过），视图层经 t() 渲染。
 *
 * 只校验「说得出原因」的条件字段。密码**不作为必填** —— 无认证实例是合法配置。
 */
export const validateProfileForm = (form: ProfileForm): FormMessage[] => {
    const errors: FormMessage[] = []

    if (form.name.trim() === '') {
        errors.push({key: 'profileManage.form.nameRequired'})
    }

    switch (form.mode) {
        case 'STANDALONE': {
            if (form.host.trim() === '') {
                errors.push({key: 'profileManage.form.hostRequired'})
            }
            if (!isValidPort(form.port)) {
                errors.push({key: 'profileManage.form.portRequired'})
            }
            break
        }

        case 'CLUSTER': {
            const nodes = collectNodes(form.nodes)
            if (nodes.length === 0) {
                errors.push({key: 'profileManage.form.nodesRequired'})
            } else {
                // 已填 host 但端口缺失的行单独提示"端口"，避免和"至少一个节点"混淆
                form.nodes
                    .filter((r) => r.host.trim() !== '')
                    .forEach((r, i) => {
                        if (!isValidPort(r.port)) {
                            errors.push({key: 'profileManage.form.portRequired', params: {index: i + 1}})
                        }
                    })
            }
            break
        }

        case 'SENTINEL': {
            const sentinels = collectNodes(form.sentinels)
            if (sentinels.length === 0) {
                errors.push({key: 'profileManage.form.nodesRequired'})
            } else {
                form.sentinels
                    .filter((r) => r.host.trim() !== '')
                    .forEach((r, i) => {
                        if (!isValidPort(r.port)) {
                            errors.push({key: 'profileManage.form.portRequired', params: {index: i + 1}})
                        }
                    })
            }
            if (form.masterName.trim() === '') {
                errors.push({key: 'profileManage.form.masterRequired'})
            }
            break
        }
    }

    // 密码**不作为必填**：无认证实例（最常见的内网 Redis）是合法配置。
    // 留空即"不设置密码"，由后端 @Size/必填校验兜底语义。

    return errors
}

// ---------------------------------------------------------------
// 请求体构建
// ---------------------------------------------------------------

/**
 * 表单 → upsert 请求体。
 *
 * 三条不变量（与后端 ProfileStore 严格对齐）：
 * 1. **只提交当前部署模式用得到的字段** —— 切换模式不该顺手清掉另一种模式的节点配置；
 * 2. **秘密字段三态**：非空 → 新值；空且标记清除 → 空串；空且未标记 → **省略键**；
 * 3. `database` 恒提交（单机/哨兵用；集群后端强制 db0，忽略该值）。
 */
export const buildProfilePayload = (form: ProfileForm): ProfileUpsertPayload => {
    const payload: ProfileUpsertPayload = {
        name: form.name.trim(),
        mode: form.mode,
        database: form.database ?? 0,
    }

    if (form.username.trim() !== '') {
        payload.username = form.username.trim()
    }

    switch (form.mode) {
        case 'STANDALONE':
            payload.host = form.host.trim()
            payload.port = form.port as number
            break
        case 'CLUSTER': {
            const nodes = collectNodes(form.nodes)
            if (nodes.length > 0) payload.nodes = nodes
            break
        }
        case 'SENTINEL': {
            const sentinels = collectNodes(form.sentinels)
            if (sentinels.length > 0) payload.sentinels = sentinels
            if (form.masterName.trim() !== '') payload.masterName = form.masterName.trim()
            break
        }
    }

    // 数据密码（集群也可用：集群每个种子 URI 都带认证）
    applySecret(payload, form, 'password')
    // 哨兵密码仅哨兵模式有意义
    if (form.mode === 'SENTINEL') {
        applySecret(payload, form, 'sentinelPassword')
    }

    return payload
}

/**
 * 测连请求体 = upsert 请求体 + 可选 id。
 *
 * 带 id 且省略密码时后端从库里补全 —— 这正是"编辑已有连接时直接点测连
 * 不必重输密码"的实现方式。
 */
export const buildValidatePayload = (
    form: ProfileForm,
    editing: boolean,
    profileId?: number | null,
): ProfileValidatePayload => {
    const payload = buildProfilePayload(form) as ProfileValidatePayload
    if (editing && profileId != null) {
        payload.id = profileId
    }
    return payload
}

/** 秘密字段三态的落地点（见 {@link buildProfilePayload} 的不变量 2）。 */
const applySecret = (
    payload: ProfileUpsertPayload,
    form: ProfileForm,
    field: SecretField,
): void => {
    const value = form[field] ?? ''
    if (value !== '') {
        // ProfileUpsertPayload 无索引签名，动态键写入须先经 unknown
        ;(payload as unknown as Record<string, unknown>)[field] = value
        return
    }
    if (isCleared(form, field)) {
        ;(payload as unknown as Record<string, unknown>)[field] = ''
    }
    // 否则省略键 —— 后端据此保持库中原值。绝不能传空串（那等于清空密码）。
}