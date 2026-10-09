import axios, {type AxiosInstance} from 'axios'
import {bootLog} from '../composables/bootLog'
import {i18n} from '../i18n'

/**
 * 恒 200 信封拦截器 —— 本项目与 kafkaVisual5 最关键的差异点。
 *
 * 后端 GlobalExceptionHandler 把一切异常都包成 `ApiResponse.error(code, msg)` 并**伴随 HTTP 200**，
 * 因此 axios 的成功回调（而非失败回调）才是业务错误的主路径：
 * 成功路径必须判 code，code≠0 一律 reject 成 ApiError。
 *
 * 失败回调只处理网络层错误（断网、超时、5xx 网关等），此时尽量从响应体里
 * 抢救 code/msg（后端异常若走了真实非 200 状态也能带上业务码）。
 *
 * 独立成函数导出：真实 `api` 实例与测试（`__tests__/index.spec.ts`）安装的是
 * **同一段代码** —— 若把拦截器逻辑内联进 create 调用，测试只能复刻实现，
 * 改坏真源码时测试照样全绿，那种测试毫无价值。
 */
export const applyEnvelopeInterceptors = (instance: AxiosInstance): void => {
    instance.interceptors.response.use(
        // 返回类型放宽为 any：本项目的约定是拦截器直接返回信封体 {code,msg,data}
        //（而非 AxiosResponse），调用方全部按 ApiResponse<T> 消费。
        (res): any => {
            const body = res.data as ApiResponse<unknown> | undefined
            // 非信封响应（如 Tauri 静态资源、代理错误页）原样透传，不误判
            if (!body || typeof body !== 'object' || typeof body.code !== 'number') {
                return body
            }
            if (body.code !== 0) {
                return Promise.reject(new ApiError(body.msg || 'Request failed', body.code, res.status))
            }
            // 成功才返回 body，调用方拿 {code, msg, data}
            return body
        },
        (err) => {
            const msg = err.response?.data?.msg || err.message || 'Request failed'
            const code = err.response?.data?.code
            const status = err.response?.status
            return Promise.reject(new ApiError(msg, code, status))
        },
    )
}

const api = axios.create({
    baseURL: '/api',
    timeout: 15000,
})

applyEnvelopeInterceptors(api)

/**
 * Tauri 桌面模式 baseURL 握手。
 *
 * - 浏览器 / vite dev：维持默认 '/api'（vite proxy → 8080），返回 [];
 * - Tauri：轮询 `invoke('backend_port')` 拿 sidecar 实际监听端口，
 *   把 baseURL 改指 `http://127.0.0.1:{port}/api`。
 *
 * 必须轮询：Spring Boot 从 spawn 到监听端口需要 5–30s，启动瞬间单次 invoke
 * 必然拿到 null（这正是「仪表盘空白」的根因——baseURL 永久落在 '/api'）。
 * 拿到端口立即返回 []；60s 超时说明 sidecar 起失败 —— 拉取壳侧收集的
 * startup_errors 一并返回，由调用方（main.ts）弹框展示，同时保持 '/api' 兜底挂载。
 *
 * @returns 启动期错误列表（空数组 = 一切正常）
 */
export const initApiBaseUrl = async (): Promise<string[]> => {
    // 用 globalThis 而非 window：vitest(node 环境)下没有 window，直接访问会 ReferenceError
    const tauri = (
        globalThis as unknown as {
            __TAURI_INTERNALS__?: { invoke?: (cmd: string) => Promise<unknown> }
        }
    ).__TAURI_INTERNALS__
    if (!tauri || typeof tauri.invoke !== 'function') {
        bootLog('浏览器/开发模式:跳过握手,走 vite proxy → 8080')
        return []
    }
    bootLog('桌面模式:开始轮询 sidecar 端口(最长 60s)')
    for (let attempt = 0; attempt < 120; attempt++) {
        try {
            const port = Number(await tauri.invoke('backend_port'))
            if (Number.isInteger(port) && port > 0 && port < 65536) {
                api.defaults.baseURL = `http://127.0.0.1:${port}/api`
                bootLog(`握手成功:baseURL = http://127.0.0.1:${port}/api`)
                return []
            }
        } catch (e: any) {
            bootLog(`第 ${attempt + 1} 次握手异常:${e?.message ?? e}(继续重试)`)
        }
        await new Promise((resolve) => setTimeout(resolve, 500))
    }
    bootLog('握手超时(60s):后端服务未就绪')
    // 60s 超时：sidecar 没起来。拉取壳侧记录的启动错误（旧版壳无此 command 时兜底空数组）
    let errors: string[] = []
    try {
        const raw = await tauri.invoke('startup_errors')
        if (Array.isArray(raw)) errors = raw.map(String)
    } catch {
        // 忽略，走通用文案
    }
    if (errors.length === 0) {
        errors.push(i18n.global.t('app.startup.notReadyIn60s'))
    }
    return errors
}

/**
 * 统一响应信封。
 *
 * ⚠️ **本项目后端恒返回 HTTP 200**（含错误场景），错误只体现在响应体 `code`。
 * 这与 kafkaVisual5「错误伴随 HTTP 状态码、成功路径无需判 code」的经验**相反**，
 * 故 `data` 声明为可选：无返回值写操作（删除 / 改 TTL / 设分等）后端
 * `ApiResponse.success()` 的 data 为 null，且 ApiResponse 标了 @JsonInclude(NON_NULL)，
 * 该字段被整体省略 → 消费侧读到 undefined。
 */
export interface ApiResponse<T> {
    code: number
    msg: string
    data?: T
}

/**
 * 携带业务错误码与 HTTP 状态的错误。
 * 拦截器把响应体的 code / HTTP status 挂回来，让组件层能按码分支，
 * 而不是只拿 message（后端 msg 已是中文可直接展示，但语义分支必须靠 code）。
 *
 * 错误码（后端 ErrorCode，5 位，不新增）：
 * - 40001 校验失败（参数非法 / 名称重复 / 集群 db>0 / 大 Key 拒绝全量 / 元素超上限）
 * - 40401 连接配置不存在
 * - 40402 Key 不存在
 * - 40902 类型冲突（key 实际类型与操作类型不符）
 * - 50302 Redis 不可用（超时 / 哨兵找不到 master / cluster_state != ok）
 * - 50001 Redis 命令执行失败
 * - 50000 系统异常
 */
export class ApiError extends Error {
    code?: number
    status?: number

    constructor(message: string, code?: number, status?: number) {
        super(message)
        this.name = 'ApiError'
        this.code = code
        this.status = status
    }
}

/** 后端 5 位错误码常量（与后端 ErrorCode 同号同义，前端按码分支时引用此处而非裸数字）。 */
export const ErrorCode = {
    VALIDATION_ERROR: 40001,
    PROFILE_NOT_FOUND: 40401,
    KEY_NOT_FOUND: 40402,
    TYPE_MISMATCH: 40902,
    REDIS_UNAVAILABLE: 50302,
    REDIS_COMMAND_FAILED: 50001,
    SYSTEM_ERROR: 50000,
} as const

// ---------------------------------------------------------------
// 路径段安全（设计 §6.2）
// ---------------------------------------------------------------

/**
 * 路径段转义。Redis key 含 `/` 是常态，而 `{key}` 是**单段**路径变量。
 *
 * `encodeURIComponent` **不转义 `/`**，必须额外替换为 `%2F`，否则
 * `user/1` 会被拆成两段、后端拿到错误的 key。
 *
 * ⚠️ **联调风险（后端【待确认】#1）**：Tomcat 默认拒绝 encoded slash，
 * `%2F` 可能被容器层 400 拒绝。联调时若含 `/` 的 key 操作报 400，
 * 需与后端确认开 `ALLOW_ENCODED_SLASH` 或补 query 传 key 的端点变体。
 */
export const encodeSegment = (s: string | number): string =>
    encodeURIComponent(String(s)).replace(/\//g, '%2F')

/**
 * 拼装 `/api/c/{profileId}` 前缀后的路径。
 * 用法：`c(id, `/keys/${encodeSegment('user/1')}`)` → `/c/1/keys/user%2F1`
 */
export const c = (profileId: number, path: string): string => `/c/${profileId}${path}`

// ---------------------------------------------------------------
// Types（逐字对齐后端 VO / DTO）
// ---------------------------------------------------------------

/** 部署形态。决定后端建 client 的路径。 */
export type DeployMode = 'STANDALONE' | 'CLUSTER' | 'SENTINEL'

/** 运行时连接状态（纯内存态，不落库）。注意是 CONNECTED 而非 ONLINE。 */
export type ProfileState = 'CONNECTED' | 'OFFLINE'

/** 凭据是否已配置。后端**永不回密码明文**，只回这个存在性标记。 */
export type CredentialPresence = 'NONE' | 'PRESENT'

export interface NodeVO {
    host: string
    port: number
}

export interface ProfileVO {
    id: number
    name: string
    mode: DeployMode
    host?: string | null
    port?: number | null
    nodes?: NodeVO[] | null
    sentinels?: NodeVO[] | null
    masterName?: string | null
    database?: number | null
    username?: string | null
    /** 与 sentinelCredentialPresence 是**两个独立字段**（v1 误设为对象） */
    credentialPresence?: CredentialPresence
    sentinelCredentialPresence?: CredentialPresence
    profileState: ProfileState
}

export interface ProfileNodePayload {
    host: string
    port: number
}

/**
 * 连接配置 upsert 请求体。
 *
 * **秘密三态**（后端 ProfileStore 契约）：
 * - 字段缺省（undefined）或 `'******'` → 保持库中现值
 * - 空串 `''` → 清空
 * - 其他值 → AES-GCM 加密后存
 *
 * 所以「用户没动密码框」必须表达为**键不存在**，绝不能传空串（那等于清空密码）。
 */
export interface ProfileUpsertPayload {
    name: string
    mode: DeployMode
    /** STANDALONE 专用 */
    host?: string
    port?: number
    /** CLUSTER 专用 */
    nodes?: ProfileNodePayload[]
    /** SENTINEL 专用 */
    sentinels?: ProfileNodePayload[]
    masterName?: string
    database?: number
    username?: string
    /** 三态：undefined 保持 / '' 清空 / 非空替换 */
    password?: string
    /** 三态，同 password；与数据密码相互独立 */
    sentinelPassword?: string
}

/** 测连请求体：upsert 主体 + 可选 id（带 id 且省略密码时后端从库里补全）。 */
export interface ProfileValidatePayload extends Omit<ProfileUpsertPayload, 'name'> {
    id?: number
    name?: string
}

/**
 * 测连结果。**恒 code=0** —— 不可达不是错误码，而是 `reachable=false` + error 文案。
 * 前端只看 data，不做错误码分支。
 */
export interface ValidateResultVO {
    reachable: boolean
    latencyMs: number
    nodeCount: number
    mode: DeployMode
    error: string | null
}

export interface DatabaseInfoVO {
    db: number
    keys: number
}

/** 跨实例聚合。离线实例不拖垮整体响应。 */
export interface DashboardOverviewVO {
    totalProfiles: number
    onlineProfiles: number
    offlineProfiles: number
    memoryUsageBytes: number
    memoryUsageHuman: string
    peakMemoryBytes: number
    clients: number
    opsPerSec: number
    totalKeys: number
    instances: InstanceMetricsVO[]
}

export interface InstanceMetricsVO {
    profileId: number
    name: string
    mode: DeployMode
    profileState: ProfileState
    nodeCount?: number | null
    masterCount?: number | null
    slaveCount?: number | null
    clusterState?: string | null
    memoryUsageBytes?: number | null
    memoryUsageHuman?: string | null
    peakMemoryBytes?: number | null
    maxMemoryBytes?: number | null
    memoryUsagePercent?: number | null
    clients?: number | null
    opsPerSec?: number | null
    /**
     * **差分值**：后端启动后需约 10s 才有首个差分，之前为 null（显示"—"）。
     * 离线时后端给 0.0 —— 与 null 语义不同：0.0 是真值，null 是「尚无采样」。
     */
    cpuUsagePercent?: number | null
    totalKeys?: number | null
    hitRate?: number | null
    latencyMs?: number | null
}

export interface ClusterNodeVO {
    id?: string | null
    addr: string
    role?: string | null
    flags?: string[] | null
    slots?: string[] | null
    connected?: boolean | null
    masterId?: string | null
    memoryUsageBytes?: number | null
    memoryUsageHuman?: string | null
    clients?: number | null
    opsPerSec?: number | null
}

export interface ClusterTopologyVO {
    profileId: number
    name?: string | null
    mode?: string | null
    state?: string | null
    slotsAssigned?: number | null
    slotsOk?: number | null
    nodes: ClusterNodeVO[]
}

export interface MemoryPointVO {
    profileId: number
    name?: string | null
    usedMemoryBytes?: number | null
}

export interface MemorySampleVO {
    timestamp: string
    points: MemoryPointVO[]
}

export interface MemoryTrendVO {
    samples: MemorySampleVO[]
}

export interface LoadPointVO {
    profileId: number
    name?: string | null
    opsPerSec?: number | null
    clients?: number | null
}

export interface LoadSampleVO {
    timestamp: string
    points: LoadPointVO[]
}

export interface LoadTrendVO {
    samples: LoadSampleVO[]
}

/**
 * SCAN 分页结果。
 *
 * - `keys` 是**纯字符串数组**（v1 误设为 KeySummary[]）：K1 只返回 key 名，类型/TTL 属详情域；
 * - `nextCursor` 是 **string**：集群为三段式 `host|port|nodeCursor`，
 *   前端**只透传、绝不解析**；
 * - `exhausted` 是唯一判停依据。
 */
export interface KeyScanVO {
    nextCursor: string
    keys: string[]
    exhausted: boolean
}

/** key 详情。encoding / memoryUsageBytes 标了 NON_NULL，可能整体省略（undefined）。 */
export interface KeyDetailVO {
    key: string
    type: string
    size: number
    ttlSeconds: number
    ttlFormat: string
    exists: boolean
    encoding?: string
    memoryUsageBytes?: number
}

/** String 值。>1MB 时后端截断预览并回 truncated=true + 完整 length。 */
export interface StringValueVO {
    value: string
    length: number
    truncated: boolean
}

/** List 区间读取。total 为 LLEN 真值（可跳页）。 */
export interface ListRangeVO {
    total: number
    items: string[]
}

export interface HashFieldVO {
    field: string
    value: string
    truncated: boolean
}

/**
 * HSCAN 分页结果。
 * **无 exhausted 字段** —— 以 `nextCursor === 0` 判耗尽；游标是 number（与 K1 的 string 不同）。
 */
export interface HashScanVO {
    nextCursor: number
    fields: HashFieldVO[]
}

/** SSCAN 分页结果。同样以 `nextCursor === 0` 判耗尽，无 exhausted 字段。 */
export interface SetScanVO {
    nextCursor: number
    members: string[]
    size: number
}

export interface ZSetMemberVO {
    member: string
    score: number
}

/** ZSet 区间/分页读取。total 为 ZCARD 真值。 */
export interface ZSetRangeVO {
    total: number
    items: ZSetMemberVO[]
}

/** Z6 rank。**rank 从 0 起**（Redis 原生语义），不是 1。 */
export interface ZSetRankVO {
    rank: number
    score: number
}

/** 操作日志单条（Y1）。eventTime 为 ISO-8601 本地时间字符串。 */
export interface KeyHistoryVO {
    id: number
    profileId: number
    db?: number | null
    keyName: string
    keyType?: string | null
    operation: string
    valuePreview?: string | null
    valueBytes?: number | null
    operator?: string | null
    eventTime: string
}

/** 操作日志分页（**页码分页**，与 SCAN 游标语义不同）。 */
export interface KeyHistoryPageVO {
    total: number
    page?: number | null
    pageSize?: number | null
    items: KeyHistoryVO[]
}

export interface KeySearchHitVO {
    key: string
    type: string
    size: number
    ttlSeconds: number
    ttlFormat: string
    matchedPreview?: string | null
    truncated?: boolean | null
}

/**
 * 实时检索结果。
 *
 * - `exhausted=false` 表示撞上扫描预算（后端 search.max-keys-per-request）
 *   或命中数上限 —— 是「**部分结果**」而**不是错误**，UI 须明示；
 * - `scanned / budget` 用于展示扫描进度。
 */
export interface KeySearchVO {
    items: KeySearchHitVO[]
    scanned: number
    exhausted: boolean
    budget: number
}

/** INFO 分节。keyspace 等小节可能为 null（Redis 版本/配置差异）。 */
export interface InfoVO {
    server?: Record<string, string> | null
    clients?: Record<string, string> | null
    memory?: Record<string, string> | null
    persistence?: Record<string, string> | null
    stats?: Record<string, string> | null
    replication?: Record<string, string> | null
    cpu?: Record<string, string> | null
    keyspace?: Record<string, string> | null
}

export interface ClientInfoVO {
    id?: string | null
    addr?: string | null
    db?: string | null
    age?: string | null
    idle?: string | null
    cmd?: string | null
}

/** 慢查询日志单条。durationUs 为**微秒**。 */
export interface SlowLogVO {
    id?: number | null
    timestamp?: string | null
    durationUs?: number | null
    command?: string | null
}

export interface ServerOverviewVO {
    usedMemory?: string | null
    usedMemoryBytes?: number | null
    peakMemory?: string | null
    connectedClients?: number | null
    totalCommands?: number | null
    opsPerSec?: number | null
    keyspaceHits?: number | null
    keyspaceMisses?: number | null
    hitRate?: number | null
    uptimeDays?: number | null
    redisVersion?: string | null
}

// ---- 请求体（DTO） ----

export interface KeyTtlPayload {
    /** -1 = 永久(PERSIST)；0 = **立即删除**；>0 = 秒数；< -1 后端拒绝 */
    ttlSeconds: number
}

export interface KeyRenamePayload {
    newKey: string
}

/** S2 写 String：**key 在请求体中**，不在路径上。 */
export interface StringSetPayload {
    key: string
    value: string
    ttlSeconds?: number
}

export interface StringAppendPayload {
    value: string
}

export interface ListPushPayload {
    values: string[]
    ttlSeconds?: number
}

export interface ListSetByIndexPayload {
    value: string
}

export interface HashFieldPayload {
    field: string
    value: string
    ttlSeconds?: number
}

export interface HashBatchSetPayload {
    /** 单次上限 1000 条（后端 @Size 校验）。**元素级 ttlSeconds 被忽略**，只取顶层。 */
    entries: HashFieldPayload[]
    ttlSeconds?: number
}

export interface SetMembersPayload {
    members: string[]
    ttlSeconds?: number
}

export interface ZSetMemberPayload {
    member: string
    score: number
    ttlSeconds?: number
}

export interface ZSetScorePayload {
    score: number
}

// ---------------------------------------------------------------
// 端点函数：profiles（P1~P7）
// ---------------------------------------------------------------

/** P1 全量连接配置列表（含运行时 profileState）。 */
export const getProfiles = () => api.get<unknown, ApiResponse<ProfileVO[]>>('/profiles')

/** P2 单个连接配置详情。当前 UI 用 P1 列表数据即可满足编辑回显，本函数为预留（如详情页/刷新单个）。 */
export const getProfile = (id: number) =>
    api.get<unknown, ApiResponse<ProfileVO>>(`/profiles/${id}`)

/** P3 新建。INSERT 路径秘密三态规则同 update（current=null）。 */
export const createProfile = (payload: ProfileUpsertPayload) =>
    api.post<unknown, ApiResponse<ProfileVO>>('/profiles', payload)

/** P4 更新。 */
export const updateProfile = (id: number, payload: ProfileUpsertPayload) =>
    api.put<unknown, ApiResponse<ProfileVO>>(`/profiles/${id}`, payload)

/** P5 删除。无返回值写操作 → data 整体省略。 */
export const deleteProfile = (id: number) =>
    api.delete<unknown, ApiResponse<void>>(`/profiles/${id}`)

/**
 * P6 测连。**异常全部内化为 reachable=false + error，恒 code=0** —— 不做错误码分支。
 * 带 id 且省略密码时后端从库里补全（测已有配置不必重输密码）。
 */
export const validateProfile = (payload: ProfileValidatePayload) =>
    api.post<unknown, ApiResponse<ValidateResultVO>>('/profiles/validate', payload)

/** P7 各 db 的 key 数（INFO keyspace 解析）；集群固定 `[{db:0, keys:总Key}]`。 */
export const getDatabases = (profileId: number) =>
    api.get<unknown, ApiResponse<DatabaseInfoVO[]>>(`/profiles/${profileId}/databases`)

// ---------------------------------------------------------------
// 端点函数：dashboard（D1~D4）
// ---------------------------------------------------------------

/** D1 跨实例聚合。 */
export const getDashboardOverview = () =>
    api.get<unknown, ApiResponse<DashboardOverviewVO>>('/dashboard/overview')

/** D2 集群/哨兵拓扑。**单机模式后端返 40001**（前端不提供入口）。 */
export const getTopology = (profileId: number) =>
    api.get<unknown, ApiResponse<ClusterTopologyVO>>(`/dashboard/topology/${profileId}`)

/** D3 内存趋势。数据来自内存环形缓冲，**重启即清零**。 */
export const getMemoryTrend = (seconds = 300) =>
    api.get<unknown, ApiResponse<MemoryTrendVO>>('/dashboard/memory-trend', {params: {seconds}})

/** D4 负载趋势（opsPerSec / clients）。 */
export const getLoadTrend = (seconds = 300) =>
    api.get<unknown, ApiResponse<LoadTrendVO>>('/dashboard/load-trend', {params: {seconds}})

// ---------------------------------------------------------------
// 端点函数：keys（K1~K5）
// ---------------------------------------------------------------

/**
 * K1 SCAN 分页。`cursor` 不透明，**原样回传禁止解析**（集群为 `host|port|nodeCursor` 三段式）。
 * `count` 默认 200、后端上限 1000。`type` 可选（string/list/hash/set/zset，Redis 6.0+ 服务端过滤）。
 */
export const scanKeys = (
    profileId: number,
    params: { db?: number; cursor?: string; match?: string; count?: number; type?: string } = {},
) =>
    api.get<unknown, ApiResponse<KeyScanVO>>(c(profileId, '/keys'), {
        params: {db: 0, cursor: '0', ...params},
    })

/** K2 key 详情。encoding / memoryUsageBytes 可能整体省略。 */
export const getKeyDetail = (profileId: number, key: string, db = 0) =>
    api.get<unknown, ApiResponse<KeyDetailVO>>(c(profileId, `/keys/${encodeSegment(key)}`), {params: {db}})

/** K3 删除 key，回 `{deleted: n}`。 */
export const deleteKey = (profileId: number, key: string, db = 0) =>
    api.delete<unknown, ApiResponse<{ deleted: number }>>(c(profileId, `/keys/${encodeSegment(key)}`), {
        params: {db},
    })

/**
 * K4 设 TTL。**-1 = PERSIST（永久）；0 = 立即删除该 key**（不是「无过期」！）。
 * 前端对 0 必须走红色二次确认，对 < -1 直接拦截。
 */
export const updateTtl = (profileId: number, key: string, ttlSeconds: number, db = 0) =>
    api.patch<unknown, ApiResponse<void>>(c(profileId, `/keys/${encodeSegment(key)}/ttl`), {
        params: {db},
        data: {ttlSeconds} satisfies KeyTtlPayload,
    })

/** K5 重命名。RENAMENX 语义：目标已存在 → 40001「目标 key 已存在，拒绝覆盖」。同名则后端无操作直接成功。 */
export const renameKey = (profileId: number, key: string, newKey: string, db = 0) =>
    api.patch<unknown, ApiResponse<void>>(c(profileId, `/keys/${encodeSegment(key)}/rename`), {
        params: {db},
        data: {newKey} satisfies KeyRenamePayload,
    })

// ---------------------------------------------------------------
// 端点函数：String（S1~S4）
// ---------------------------------------------------------------

/** S1 读值。>1MB 时后端截断，回 truncated=true + 完整 length。 */
export const getString = (profileId: number, key: string, db = 0) =>
    api.get<unknown, ApiResponse<StringValueVO>>(c(profileId, `/strings/${encodeSegment(key)}`), {
        params: {db},
    })

/** S2 写值。**key 在请求体**（不在路径上），可选 ttlSeconds。 */
export const setString = (profileId: number, payload: StringSetPayload, db = 0) =>
    api.post<unknown, ApiResponse<void>>(c(profileId, '/strings'), {params: {db}, data: payload})

/** S3 追加，回 `{length}` 新长度。 */
export const appendString = (profileId: number, key: string, value: string, db = 0) =>
    api.patch<unknown, ApiResponse<{ length: number }>>(
        c(profileId, `/strings/${encodeSegment(key)}/append`),
        {params: {db}, data: {value} satisfies StringAppendPayload},
    )

/** S4 删除 String key（独立端点，与 K3 通用删除并存）。当前 UI 统一走 K3，本函数为预留。 */
export const deleteStringKey = (profileId: number, key: string, db = 0) =>
    api.delete<unknown, ApiResponse<void>>(c(profileId, `/strings/${encodeSegment(key)}`), {params: {db}})

// ---------------------------------------------------------------
// 端点函数：List（L1~L6）
// ---------------------------------------------------------------

/**
 * L1 LRANGE 区间读取（闭区间）。
 *
 * ⚠️ **前端永远传 `end >= 0`**：`end=-1` 被后端判为「无界全量请求」，
 * 大 Key（元素数 > limits.large-key-threshold，默认 5000）直接 40001 拒绝。
 * 区间长度上限 5000。
 */
export const getListRange = (
    profileId: number,
    key: string,
    start: number,
    end: number,
    db = 0,
) =>
    api.get<unknown, ApiResponse<ListRangeVO>>(c(profileId, `/lists/${encodeSegment(key)}`), {
        params: {db, start, end},
    })

/** L2 头插入（LPUSH），回 `{length}`。 */
export const listPushLeft = (profileId: number, key: string, values: string[], ttlSeconds?: number, db = 0) =>
    api.post<unknown, ApiResponse<{ length: number }>>(c(profileId, `/lists/${encodeSegment(key)}/lpush`), {
        params: {db},
        data: {values, ttlSeconds} satisfies ListPushPayload,
    })

/** L2 尾插入（RPUSH），回 `{length}`。 */
export const listPushRight = (profileId: number, key: string, values: string[], ttlSeconds?: number, db = 0) =>
    api.post<unknown, ApiResponse<{ length: number }>>(c(profileId, `/lists/${encodeSegment(key)}/rpush`), {
        params: {db},
        data: {values, ttlSeconds} satisfies ListPushPayload,
    })

/** L3 按索引改值（LSET）。 */
export const listSetByIndex = (profileId: number, key: string, index: number, value: string, db = 0) =>
    api.patch<unknown, ApiResponse<void>>(c(profileId, `/lists/${encodeSegment(key)}/${index}`), {
        params: {db},
        data: {value} satisfies ListSetByIndexPayload,
    })

/**
 * L4 按索引删除。**后端是 LSET + LREM 两步、非原子** ——
 * 并发修改下会抛 50000「按索引删除失败：列表已被并发修改，请重试」，此时应重拉区间。
 */
export const listDeleteByIndex = (profileId: number, key: string, index: number, db = 0) =>
    api.delete<unknown, ApiResponse<void>>(c(profileId, `/lists/${encodeSegment(key)}/${index}`), {params: {db}})

/** L5 弹出（LPOP/RPOP）。**direction 是查询参数**（LEFT/RIGHT），回 `{value}`。 */
export const listPop = (profileId: number, key: string, direction: 'LEFT' | 'RIGHT' = 'LEFT', db = 0) =>
    api.post<unknown, ApiResponse<{ value: string }>>(c(profileId, `/lists/${encodeSegment(key)}/pop`), {
        params: {db, direction},
    })

// ---------------------------------------------------------------
// 端点函数：Hash（H1~H5）
// ---------------------------------------------------------------

/**
 * H1 HSCAN 分页。
 *
 * ⚠️ **count 必传**：不传 count 被后端判为「无界全量请求」，
 * 大 Key 直接 40001 拒绝（设计 §15.8 验收项）。游标是 number，`nextCursor===0` 判耗尽。
 */
export const scanHashFields = (
    profileId: number,
    key: string,
    params: { db?: number; cursor?: number; match?: string; count: number },
) =>
    api.get<unknown, ApiResponse<HashScanVO>>(c(profileId, `/hashes/${encodeSegment(key)}`), {
        params: {db: 0, cursor: 0, match: '*', ...params},
    })

/** H2 读单字段。**后端不截断**（truncated 恒 false），返回的是完整值。 */
export const getHashField = (profileId: number, key: string, field: string, db = 0) =>
    api.get<unknown, ApiResponse<HashFieldVO>>(
        c(profileId, `/hashes/${encodeSegment(key)}/fields/${encodeSegment(field)}`),
        {params: {db}},
    )

/** H3 写单字段。ttlSeconds 为 null/缺省时**保持原有 TTL 不变**（不清空）。 */
export const setHashField = (
    profileId: number,
    key: string,
    payload: HashFieldPayload,
    db = 0,
) =>
    api.post<unknown, ApiResponse<void>>(c(profileId, `/hashes/${encodeSegment(key)}/fields`), {
        params: {db},
        data: payload,
    })

/** H4 批量写字段。单次上限 1000 条；**元素级 ttlSeconds 被忽略**，只取顶层。 */
export const batchSetHashFields = (
    profileId: number,
    key: string,
    payload: HashBatchSetPayload,
    db = 0,
) =>
    api.put<unknown, ApiResponse<void>>(c(profileId, `/hashes/${encodeSegment(key)}/fields`), {
        params: {db},
        data: payload,
    })

/** H5 删字段，回 `{deleted}`。 */
export const deleteHashField = (profileId: number, key: string, field: string, db = 0) =>
    api.delete<unknown, ApiResponse<{ deleted: number }>>(
        c(profileId, `/hashes/${encodeSegment(key)}/fields/${encodeSegment(field)}`),
        {params: {db}},
    )

// ---------------------------------------------------------------
// 端点函数：Set（E1~E4）
// ---------------------------------------------------------------

/**
 * E1 SSCAN 分页。
 *
 * ⚠️ `count` 必传（同 H1：大 Key 防护）；后端硬钳制上限 500。
 * **无 match 参数**（Redis SSCAN 支持但后端未暴露）—— UI 不提供过滤框。
 */
export const scanSetMembers = (
    profileId: number,
    key: string,
    params: { db?: number; cursor?: number; count: number },
) =>
    api.get<unknown, ApiResponse<SetScanVO>>(c(profileId, `/sets/${encodeSegment(key)}`), {
        params: {db: 0, cursor: 0, ...params},
    })

/** E2 批量加成员，回 `{added}`（已存在的成员不计入）。 */
export const addSetMembers = (
    profileId: number,
    key: string,
    members: string[],
    ttlSeconds?: number,
    db = 0,
) =>
    api.post<unknown, ApiResponse<{ added: number }>>(c(profileId, `/sets/${encodeSegment(key)}/members`), {
        params: {db},
        data: {members, ttlSeconds} satisfies SetMembersPayload,
    })

/** E3 判成员是否存在，回 `{exists}`。 */
export const setMemberExists = (profileId: number, key: string, member: string, db = 0) =>
    api.get<unknown, ApiResponse<{ exists: boolean }>>(
        c(profileId, `/sets/${encodeSegment(key)}/members/${encodeSegment(member)}/exists`),
        {params: {db}},
    )

/** E4 删成员，回 `{removed}`。 */
export const deleteSetMember = (profileId: number, key: string, member: string, db = 0) =>
    api.delete<unknown, ApiResponse<{ removed: number }>>(
        c(profileId, `/sets/${encodeSegment(key)}/members/${encodeSegment(member)}`),
        {params: {db}},
    )

// ---------------------------------------------------------------
// 端点函数：ZSet（Z1~Z6）
// ---------------------------------------------------------------

/**
 * Z1 读取（双模式）。
 *
 * - **索引分页模式**（不给 min/max）：page 从 1 起，pageSize 上限 100；
 * - **分数区间模式**（给 min 或 max 任一）：**`page` 不生效**，后端只按区间
 *   取首批（设计上以 pageSize 为批次量）。前端在此模式下必须隐藏分页器。
 *
 * `total` 为 ZCARD 真值。
 */
export const getZSetRange = (
    profileId: number,
    key: string,
    params: {
        db?: number
        page?: number
        pageSize?: number
        order?: 'ASC' | 'DESC'
        min?: number
        max?: number
    },
) =>
    api.get<unknown, ApiResponse<ZSetRangeVO>>(c(profileId, `/zsets/${encodeSegment(key)}`), {
        params: {db: 0, page: 1, pageSize: 20, order: 'ASC', ...params},
    })

/** Z2 加成员（**NX 语义**：成员已存在则不改动、`added=0`）。 */
export const addZSetMember = (
    profileId: number,
    key: string,
    member: string,
    score: number,
    ttlSeconds?: number,
    db = 0,
) =>
    api.post<unknown, ApiResponse<{ added: number }>>(c(profileId, `/zsets/${encodeSegment(key)}/members`), {
        params: {db},
        data: {member, score, ttlSeconds} satisfies ZSetMemberPayload,
    })

/** Z3 覆盖式设分（**XX 语义**：成员不存在则不创建，`updated=0`）。 */
export const setZSetScore = (profileId: number, key: string, member: string, score: number, db = 0) =>
    api.patch<unknown, ApiResponse<{ updated: number }>>(
        c(profileId, `/zsets/${encodeSegment(key)}/members/${encodeSegment(member)}/score`),
        {params: {db}, data: {score} satisfies ZSetScorePayload},
    )

/** Z4 增量加分（ZINCRBY），回 `{score}` **新分数**。 */
export const incrZSetScore = (profileId: number, key: string, member: string, score: number, db = 0) =>
    api.patch<unknown, ApiResponse<{ score: number }>>(
        c(profileId, `/zsets/${encodeSegment(key)}/members/${encodeSegment(member)}/score/incr`),
        {params: {db}, data: {score} satisfies ZSetScorePayload},
    )

/** Z5 删成员，回 `{removed}`。 */
export const deleteZSetMember = (profileId: number, key: string, member: string, db = 0) =>
    api.delete<unknown, ApiResponse<{ removed: number }>>(
        c(profileId, `/zsets/${encodeSegment(key)}/members/${encodeSegment(member)}`),
        {params: {db}},
    )

/** Z6 查 rank。**rank 从 0 起**（Redis 原生语义）。order 决定升/降序排名口径。 */
export const getZSetRank = (
    profileId: number,
    key: string,
    member: string,
    order: 'ASC' | 'DESC' = 'ASC',
    db = 0,
) =>
    api.get<unknown, ApiResponse<ZSetRankVO>>(
        c(profileId, `/zsets/${encodeSegment(key)}/members/${encodeSegment(member)}/rank`),
        {params: {db, order}},
    )

// ---------------------------------------------------------------
// 端点函数：server（V1~V5，全部只读）
// ---------------------------------------------------------------

/**
 * V1 INFO 分节。
 * ⚠️ **不带 db 参数**（后端不接受）。集群可传 `node=host:port`（从拓扑数据填充），
 * 缺省取首个 master。`section` 下拉提供常用值但允许自定义（后端不校验，透传 Redis）。
 */
export const getServerInfo = (profileId: number, section = 'default', node?: string) =>
    api.get<unknown, ApiResponse<InfoVO>>(c(profileId, '/server/info'), {
        params: node ? {section, node} : {section},
    })

/** V2 客户端列表。 */
export const getClients = (profileId: number) =>
    api.get<unknown, ApiResponse<ClientInfoVO[]>>(c(profileId, '/server/clients'))

/** V3 慢查询日志。`durationUs` 为**微秒**，展示层需格式化。 */
export const getSlowLog = (profileId: number, count = 10) =>
    api.get<unknown, ApiResponse<SlowLogVO[]>>(c(profileId, '/server/slowlog'), {params: {count}})

/** V4 DBSIZE。集群为各 master 之和。 */
export const getDbSize = (profileId: number, db = 0) =>
    api.get<unknown, ApiResponse<{ dbsize: number }>>(c(profileId, '/server/dbsize'), {params: {db}})

/** V5 运行概览。⚠️ 后端**忽略 db 参数**，前端不传。 */
export const getServerOverview = (profileId: number) =>
    api.get<unknown, ApiResponse<ServerOverviewVO>>(c(profileId, '/server/overview'))

// ---------------------------------------------------------------
// 端点函数：操作日志（Y1）与实时检索（R1）
// ---------------------------------------------------------------

/** Y1 全部操作枚举（与后端 KeyHistoryService 记录的 Redis 命令语义一一对应）。 */
export const HISTORY_OPERATIONS = [
    'SET',
    'APPEND',
    'DEL',
    'RENAME',
    'LPUSH',
    'RPUSH',
    'LSET',
    'LREM',
    'LPOP',
    'RPOP',
    'HSET',
    'HMSET',
    'HDEL',
    'SADD',
    'SREM',
    'ZADD',
    'ZINCRBY',
    'ZREM',
] as const

export type HistoryOperation = (typeof HISTORY_OPERATIONS)[number]

/** 操作日志「删除类」操作（筛选时高亮用）。 */
export const DELETE_OPERATIONS: ReadonlySet<string> = new Set(['DEL', 'LREM', 'HDEL', 'SREM', 'ZREM'])

export interface HistoryQuery {
    /** 不传 = 全部实例（已删实例的历史只有不选才查得到；后端对不存在的 profileId 返 40401） */
    profileId?: number
    db?: number
    /** key 关键词，大小写不敏感子串匹配 */
    key?: string
    /** value 关键词，只覆盖 value_preview 预览窗口；全表扫描，建议配合 key/时间范围收窄 */
    value?: string
    operation?: HistoryOperation | string
    /** ISO-8601 本地时间，如 2026-10-06T00:00:00 */
    startTime?: string
    endTime?: string
    page?: number
    pageSize?: number
}

/**
 * Y1 操作日志查询（**页码分页**，与 SCAN 游标语义不同）。
 *
 * ⚠️ **覆盖边界**：只记录**经本平台**执行的写/删操作。外部客户端（redis-cli、
 * 业务应用）的变更与 Redis 端被动 TTL 过期**不在其中** —— Redis 原生不提供
 * key 创建/删除时间元数据，无可回溯途径。日志不是全量事实。
 *
 * `startTime > endTime` 后端返 40001，前端先校验。
 */
export const queryHistory = (query: HistoryQuery) =>
    api.get<unknown, ApiResponse<KeyHistoryPageVO>>('/history/keys', {params: query})

export interface SearchQuery {
    db?: number
    /** key 关键词：走 Redis 原生 SCAN MATCH（glob，**大小写敏感**） */
    key?: string
    /** value 关键词：逐 key 读值做包含匹配（**大小写不敏感**）。成本高，UI 须提示 */
    value?: string
    limit?: number
}

/**
 * R1 实时数据检索（检索**当前存活**键空间，用 SCAN 不用 KEYS）。
 *
 * ⚠️ **key 与 value 至少提供一个**，都为空后端直接 40001（不会默默跑满预算）；
 * ⚠️ `profileId` 是**查询参数**而非路径段（本端点不挂在 /api/c/{id} 下）。
 *
 * 结果可能是部分的：`exhausted=false` 表示撞上扫描预算或命中上限。
 */
export const searchKeys = (profileId: number, query: SearchQuery) =>
    api.get<unknown, ApiResponse<KeySearchVO>>('/search/keys', {params: {profileId, db: 0, ...query}})