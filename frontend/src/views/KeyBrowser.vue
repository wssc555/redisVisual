<template>
  <div class="page">
    <div class="page-head">
      <h3 class="page-title">{{ t('keyBrowse.title') }}</h3>
      <div class="head-right">
        <DbSelector @load-failed="onDbLoadFailed"/>
        <!-- 双模式切换：SCAN 浏览 / 值检索 -->
        <el-radio-group v-model="mode" size="small">
          <el-radio-button value="scan">{{ t('keyBrowse.mode.scan') }}</el-radio-button>
          <el-radio-button value="search">{{ t('keyBrowse.mode.search') }}</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <!-- 零连接 / 未选实例引导 -->
    <el-empty v-if="profiles.length === 0" :description="t('keyBrowse.empty.noProfile')">
      <el-button type="primary" @click="router.push('/profiles')">
        {{ t('keyBrowse.empty.goManage') }}
      </el-button>
    </el-empty>
    <el-alert
        v-else-if="!profileId"
        class="offline-banner"
        :title="t('keyBrowse.empty.noProfileSelected')"
        type="info"
        :closable="false"
        show-icon
    />

    <!-- 50302 离线态横幅 -->
    <el-alert
        v-if="unreachable"
        class="offline-banner"
        :title="t('common.offlineBanner.title')"
        :description="t('common.offlineBanner.hint')"
        type="error"
        show-icon
    >
      <template #default>
        <el-button size="small" @click="retryAll">{{ t('common.retry') }}</el-button>
        <el-button size="small" @click="router.push('/profiles')">
          {{ t('profileManage.title') }}
        </el-button>
      </template>
    </el-alert>

    <div v-else-if="profileId" class="split">
      <!-- 左栏：db 选择 + 检索/浏览 -->
      <div class="left-pane">
        <KeySearchPanel
            v-if="mode === 'search'"
            :key="`search-${profileId}-${db}`"
            :profile-id="profileId"
            :db="db"
            @select="onSelectFromSearch"
        />
        <KeyTable
            v-else
            :keys="scanItems"
            :selected-key="selectedKey"
            :loading="scanLoading"
            :has-more="scanHasMore"
            :match="matchInput"
            :type="typeFilter"
            @select="onSelectKey"
            @load-more="onLoadMore"
            @update:match="onMatchInput"
            @update:type="onTypeFilter"
        />
      </div>

      <!-- 右栏：key 详情 + 类型分发 -->
      <div class="right-pane">
        <KeyDetailPane
            :profile-id="profileId"
            :db="db"
            :key-name="selectedKey"
            :detail="detail"
            :rename-conflict="renameConflict"
            @gone="removeSelectedFromList"
            @type-mismatch="onTypeMismatch"
            @refresh-detail="loadDetail"
            @renamed="onRenamed"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import {computed, onBeforeUnmount, ref, watch} from 'vue'
import {useRouter} from 'vue-router'
import {useI18n} from 'vue-i18n'
import {ElMessage} from 'element-plus'
import DbSelector from '../components/DbSelector.vue'
import KeyDetailPane from '../components/KeyDetailPane.vue'
import KeySearchPanel from '../components/KeySearchPanel.vue'
import KeyTable from '../components/KeyTable.vue'
import {ApiError, ErrorCode, getKeyDetail, type KeyDetailVO, renameKey, scanKeys,} from '../api'
import {injectGlobalState} from '../composables/useGlobalState'
import {useScanPagination} from '../composables/useScanPagination'

const {t} = useI18n()
const router = useRouter()

const {profiles, activeProfileId, activeDb} = injectGlobalState()

const profileId = computed(() => activeProfileId.value ?? 0)
const db = computed(() => activeDb.value)

/** 'scan' = SCAN 浏览（默认）；'search' = 值检索 */
const mode = ref<'scan' | 'search'>('scan')
/** 50302：当前实例不可达，页面切离线态 */
const unreachable = ref(false)

/** 选中的 key 放组件内，**不进 URL** —— Redis key 含 / ? # % 空格是常态 */
const selectedKey = ref<string | null>(null)
const detail = ref<KeyDetailVO | null>(null)
/** 改名冲突原文（后端 40001），透传给详情面板的 RenameDialog 展示 */
const renameConflict = ref('')

/** match 输入框的即时值（未防抖）；游标重置由防抖后的 match 驱动 */
const matchInput = ref('')

/** 类型筛选（小写，后端白名单校验）；'' = 不过滤 */
const typeFilter = ref('')

/**
 * K1 SCAN 游标分页：**string 游标模式** —— 有 exhausted 字段；
 * 集群游标是 `host|port|nodeCursor` 三段式，本组件**只透传不解析**。
 */
const pagination = useScanPagination<string, string>({
  mode: 'string',
  fetchPage: async (cursor) => {
    const res = await scanKeys(profileId.value, {
      db: db.value,
      cursor,
      // match 为空时不下发该参数（后端 match 可选）
      match: matchInput.value.trim() || undefined,
      type: typeFilter.value || undefined,
      count: 200,
    })
    return {
      items: res.data?.keys ?? [],
      nextCursor: res.data?.nextCursor ?? '0',
      exhausted: res.data?.exhausted ?? true,
    }
  },
})

const scanItems = pagination.items
const scanLoading = pagination.loading
const scanHasMore = pagination.hasMore
const scanReset = pagination.reset
const scanLoadMore = pagination.loadMore

const onLoadMore = () => {
  scanLoadMore()
}

/** match 防抖 500ms：边打字边重置游标会把后端打爆 */
let matchTimer: number | null = null
const onMatchInput = (value: string) => {
  matchInput.value = value
  if (matchTimer !== null) window.clearTimeout(matchTimer)
  matchTimer = window.setTimeout(() => {
    if (matchTimer !== null) {
      window.clearTimeout(matchTimer)
      matchTimer = null
    }
    scanReset().catch(() => {
    })
  }, 500)
}

/**
 * 类型切换立即重扫（无需防抖 —— 是离散选择而非连续输入）；
 * 切换后旧游标下的类型分布已失效，必须从头扫。
 */
const onTypeFilter = (value: string | undefined) => {
  typeFilter.value = value ?? ''
  scanReset().catch(() => {
  })
}

const onSelectFromSearch = (key: string) => {
  selectedKey.value = key
  renameConflict.value = ''
  loadDetail()
}

const onSelectKey = (key: string) => {
  selectedKey.value = key
  renameConflict.value = ''
  loadDetail()
}

const loadDetail = async () => {
  const key = selectedKey.value
  if (key == null) {
    detail.value = null
    return
  }
  try {
    const res = await getKeyDetail(profileId.value, key, db.value)
    // 竞态守卫：快速连点两个 key 时，旧请求可能后到 —— 只接受仍处于选中态的响应
    if (selectedKey.value !== key) return
    detail.value = res.data ?? null
    // 详情加载成功即视为实例可达
    unreachable.value = false
  } catch (e: any) {
    if (selectedKey.value !== key) return
    detail.value = null
    handleApiError(e)
  }
}

/**
 * 错误码分支（设计 §6.6）：
 * - 40402 key 已删/过期 → 提示 + 从左栏移除该行；
 * - 40902 类型变化 → 提示 + 自动重拉 K2；
 * - 50302 → 切离线态横幅；
 * - 40001 且 msg 含「大 Key」→ 引导分页。
 */
const handleApiError = (e: any) => {
  if (!(e instanceof ApiError)) {
    ElMessage.error(t('keyBrowse.detail.loadFailed', {msg: e.message}))
    return
  }
  switch (e.code) {
    case ErrorCode.KEY_NOT_FOUND:
      ElMessage.info(t('keyBrowse.detail.keyGone'))
      removeSelectedFromList()
      break
    case ErrorCode.TYPE_MISMATCH:
      ElMessage.warning(t('keyBrowse.detail.typeChanged'))
      // 自动重拉 K2 —— 详情域（而非列表域）才是类型的权威来源
      loadDetail()
      break
    case ErrorCode.REDIS_UNAVAILABLE:
      unreachable.value = true
      break
    case ErrorCode.VALIDATION_ERROR:
      if (e.message.includes('大 Key')) {
        ElMessage.warning(`${t('common.largeKey.hint')}\n${e.message}`)
      } else if (e.message.includes('索引')) {
        ElMessage.warning(e.message)
        loadDetail()
      } else {
        ElMessage.error(e.message)
      }
      break
    default:
      ElMessage.error(e.message)
  }
}

/** key 不存在：从左栏移除该行并清空选中态 */
const removeSelectedFromList = () => {
  const key = selectedKey.value
  if (key != null) {
    const idx = scanItems.value.indexOf(key)
    if (idx >= 0) scanItems.value.splice(idx, 1)
  }
  selectedKey.value = null
  detail.value = null
}

const onTypeMismatch = () => {
  loadDetail()
}

const onRenamed = async (newKey: string) => {
  const oldKey = selectedKey.value
  if (oldKey == null) return
  // 乐观清掉上一次的冲突提示；改名请求期间 RenameDialog 保持打开，
  // 成功后由 selectedKey 变化驱动 KeyDetailPane 收起对话框，失败则就地显示冲突原文。
  renameConflict.value = ''
  try {
    await renameKey(profileId.value, oldKey, newKey, db.value)
    ElMessage.success(t('keyBrowse.rename.updated'))
    // 同步左栏行名与选中态；selectedKey 变化即"成功"信号，对话框随之关闭
    const idx = scanItems.value.indexOf(oldKey)
    if (idx >= 0) scanItems.value[idx] = newKey
    selectedKey.value = newKey
    loadDetail()
  } catch (e: any) {
    // RENAMENX 冲突：后端 40001「目标 key 已存在，拒绝覆盖: xxx」
    // 原样传给详情面板展示 —— 后端文案已带完整上下文，比前端拼装更权威。
    if (e instanceof ApiError && e.code === ErrorCode.VALIDATION_ERROR) {
      renameConflict.value = e.message
      return
    }
    handleApiError(e)
  }
}

const retryAll = () => {
  unreachable.value = false
  scanReset().catch(() => {
  })
  loadDetail()
}

const onDbLoadFailed = (msg: string) => {
  ElMessage.warning(t('keyBrowse.db.loadFailed', {msg}))
}

// 实例 / db / 模式切换 → 重新扫描并清空选中态
watch(
    () => [profileId.value, db.value],
    () => {
      selectedKey.value = null
      detail.value = null
      unreachable.value = false
      if (mode.value === 'scan') {
        scanReset().catch(handleApiError)
      }
    },
    {immediate: true},
)

// 从检索模式切回浏览模式：**无条件重扫**。检索期间 db/实例可能已切换，
// 旧列表残留会展示在新 db 语境下（点击后 40402 误删行），不能靠 items.length 判断。
watch(mode, (m) => {
  if (m === 'scan') {
    scanReset().catch(handleApiError)
  }
})

// 卸载清理：防抖 timer 若在卸载后触发，scanReset 会操作已卸载组件的状态
onBeforeUnmount(() => {
  if (matchTimer !== null) {
    window.clearTimeout(matchTimer)
    matchTimer = null
  }
})
</script>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  gap: 12px;
}

.page-title {
  margin: 0;
  font-size: 16px;
}

.head-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.offline-banner {
  margin-bottom: 12px;
}

.split {
  display: grid;
  grid-template-columns: minmax(280px, 1fr) minmax(0, 2fr);
  gap: 16px;
  align-items: start;
}

@media (max-width: 1023.98px) {
  /* 窄档改为纵向堆叠，避免两栏都被压扁 */
  .split {
    grid-template-columns: 1fr;
  }
}

.left-pane,
.right-pane {
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 12px;
  min-width: 0;
}
</style>