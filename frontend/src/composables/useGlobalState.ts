import {computed, type ComputedRef, inject, type InjectionKey, provide, ref, type Ref} from 'vue'
import {type DatabaseInfoVO, getDatabases, getProfiles, type ProfileVO} from '../api'

/**
 * 全局状态（模块级惰性单例 + provide/inject，**不引 Pinia**，同构 kafkaVisual5）。
 *
 * 核心范式「激活实例」：全局持 `activeProfileId` + `activeDb`，顶栏切换即时生效，
 * 各视图 watch 后自行重取。`activeProfileId === null` 时功能页展示空态引导跳 /profiles。
 */
export interface GlobalState {
    /** 全量连接配置列表（含运行时 profileState，来自 P1） */
    profiles: Ref<ProfileVO[]>
    /** 是否已完成首次 loadProfiles（无论成败）—— 零连接引导的依据 */
    profilesLoaded: Ref<boolean>
    loadProfiles: () => Promise<void>

    /** 当前激活实例 id；null = 尚未选择（启动恢复前 / 零连接） */
    activeProfileId: Ref<number | null>
    /** 激活实例对象；列表未含该 id（被删/未加载完）时为 null */
    activeProfile: ComputedRef<ProfileVO | null>
    /** 切换激活实例：重置 activeDb（集群恒 0）+ 清 SCAN 游标由各视图自行 watch 处理 */
    setActiveProfile: (id: number) => void

    /** 当前 db 索引；集群恒 0（后端对集群 db>0 直接 40001） */
    activeDb: Ref<number>
    setActiveDb: (db: number) => void

    /** 各 db 的 key 数缓存：profileId → DatabaseInfoVO[]（P7） */
    databasesByProfile: Ref<Map<number, DatabaseInfoVO[]>>
    loadDatabases: (profileId: number) => Promise<void>
    /** 当前实例的 db 列表（未加载过为空数组） */
    activeDatabases: ComputedRef<DatabaseInfoVO[]>

    /** 界面偏好 KV（后端**无 preferences 端点**，纯 localStorage，不为它扩后端） */
    preferences: Ref<Record<string, string>>
    savePreference: (key: string, value: string) => void
}

export const GlobalStateKey: InjectionKey<GlobalState> = Symbol('redis-viz-global-state')

/** localStorage 键约定 */
export const STORAGE_KEYS = {
    /** 语言：与 i18n 层一致 */
    lang: 'redisviz.lang',
    activeProfileId: 'redisviz.activeProfileId',
    /** activeDb 按 profile 分键：`redisviz.db.<profileId>` */
    activeDbPrefix: 'redisviz.db.',
    /** 侧栏折叠态 */
    asideCollapsed: 'redisviz.asideCollapsed',
} as const

/** 模块级惰性单例：整个应用只创建一份 */
let singleton: GlobalState | null = null

/** localStorage 读写在 node 测试环境无该对象，读失败静默降级 */
const readLocal = (key: string): string | null => {
    try {
        return window.localStorage.getItem(key)
    } catch {
        return null
    }
}
const writeLocal = (key: string, value: string) => {
    try {
        window.localStorage.setItem(key, value)
    } catch {
        // 无 localStorage（node 测试 / 隐私模式）时忽略
    }
}
const removeLocal = (key: string) => {
    try {
        window.localStorage.removeItem(key)
    } catch {
        // ignore
    }
}

/** 集群实例的 db 恒为 0：后端对 CLUSTER 传 db>0 直接 40001 */
export const resolveDbForMode = (mode?: string | null, preferred?: number | null): number =>
    mode === 'CLUSTER' ? 0 : Math.max(0, preferred ?? 0)

/**
 * 创建（或复用）全局状态并 provide 给后代组件。
 * 仅应在 App.vue 的 setup 中调用一次。
 */
export const useGlobalState = (): GlobalState => {
    if (!singleton) {
        const profiles = ref<ProfileVO[]>([])
        const profilesLoaded = ref(false)
        const activeProfileId = ref<number | null>(null)
        const activeDb = ref(0)
        const databasesByProfile = ref<Map<number, DatabaseInfoVO[]>>(new Map())
        const preferences = ref<Record<string, string>>({})

        const activeProfile = computed(
            () => profiles.value.find((p) => p.id === activeProfileId.value) || null,
        )

        /** 恢复上次激活的 db；集群强制 0 */
        const restoreDb = (profileId: number, mode?: string | null) => {
            const raw = readLocal(STORAGE_KEYS.activeDbPrefix + profileId)
            const parsed = raw === null ? NaN : Number(raw)
            activeDb.value = resolveDbForMode(mode, Number.isInteger(parsed) ? parsed : 0)
        }

        singleton = {
            profiles,
            profilesLoaded,
            loadProfiles: async () => {
                try {
                    const res = await getProfiles()
                    profiles.value = res.data ?? []
                    // 恢复上次激活实例；值非法/实例已删时保持 null，由 ensureActiveProfile 兜底
                    const saved = Number(readLocal(STORAGE_KEYS.activeProfileId))
                    if (Number.isInteger(saved) && saved > 0) {
                        activeProfileId.value = saved
                        restoreDb(saved, profiles.value.find((p) => p.id === saved)?.mode)
                    }
                } finally {
                    // 成败都算「已尝试」：失败 + 空列表按零连接引导处理，避免无限 loading 语义
                    profilesLoaded.value = true
                }
            },

            activeProfileId,
            activeProfile,
            setActiveProfile: (id: number) => {
                if (activeProfileId.value === id) return
                activeProfileId.value = id
                writeLocal(STORAGE_KEYS.activeProfileId, String(id))
                // 切换实例必须重置 db —— 沿用上一个实例的 db 索引在新实例上多半无意义
                restoreDb(id, profiles.value.find((p) => p.id === id)?.mode)
            },

            activeDb,
            setActiveDb: (db: number) => {
                const id = activeProfileId.value
                activeDb.value = db
                if (id != null) writeLocal(STORAGE_KEYS.activeDbPrefix + id, String(db))
            },

            databasesByProfile,
            loadDatabases: async (profileId: number) => {
                const res = await getDatabases(profileId)
                databasesByProfile.value = new Map(databasesByProfile.value).set(profileId, res.data ?? [])
            },
            activeDatabases: computed(() => {
                const id = activeProfileId.value
                if (id == null) return []
                return databasesByProfile.value.get(id) ?? []
            }),

            preferences,
            savePreference: (key: string, value: string) => {
                preferences.value = {...preferences.value, [key]: value}
                writeLocal(key, value)
            },
        }
    }

    provide(GlobalStateKey, singleton)
    return singleton
}

/**
 * 读取全局状态。App.vue 尚未 provide 时（如组件被独立挂载）回退到本地默认值，
 * 保证组件不会因缺少 provider 而崩溃。
 */
export const injectGlobalState = (): GlobalState => {
    const injected = inject(GlobalStateKey, null)
    if (injected) return injected

    const noop = () => {
    }
    const noopAsync = async () => {
    }
    return {
        profiles: ref([]),
        profilesLoaded: ref(false),
        loadProfiles: noopAsync,
        activeProfileId: ref(null),
        activeProfile: computed(() => null),
        setActiveProfile: noop,
        activeDb: ref(0),
        setActiveDb: noop,
        databasesByProfile: ref(new Map()),
        loadDatabases: noopAsync,
        activeDatabases: computed(() => []),
        preferences: ref({}),
        savePreference: noop,
    }
}

/**
 * 激活实例兜底：
 * - 未选择时选第一个；
 * - 激活实例被删除后自动落到第一个（id 不复用，留着只会全页 40401）。
 *
 * 零实例时什么都不做 —— 零实例重定向在 App.vue 的 watchEffect。
 */
export const ensureActiveProfile = (state: GlobalState) => {
    if (!state.profilesLoaded.value || state.profiles.value.length === 0) return
    const current = state.activeProfileId.value
    if (current == null || !state.profiles.value.some((p) => p.id === current)) {
        state.setActiveProfile(state.profiles.value[0].id)
    }
}

/** 供 App.vue 清理实例时同步掉 localStorage 里的 db 键 */
export const forgetProfileDb = (profileId: number) => {
    removeLocal(STORAGE_KEYS.activeDbPrefix + profileId)
}