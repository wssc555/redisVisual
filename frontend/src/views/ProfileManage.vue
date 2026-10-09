<template>
  <div class="page">
    <div class="page-head">
      <h3 class="page-title">{{ t('profileManage.title') }}</h3>
      <el-button :icon="Plus" type="primary" @click="openCreate">
        {{ t('profileManage.add') }}
      </el-button>
    </div>

    <el-table v-loading="loading" :data="profiles" border stripe>
      <el-table-column :label="t('profileManage.table.name')" prop="name" min-width="140"/>
      <el-table-column :label="t('profileManage.table.mode')" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="modeTagType(row.mode)">
            {{ t(`profileManage.mode.${row.mode}`) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('profileManage.table.address')" min-width="200">
        <template #default="{ row }">
          <span v-if="row.mode === 'STANDALONE'">{{ row.host }}:{{ row.port }}</span>
          <span v-else-if="row.mode === 'CLUSTER'">
            {{ t('profileManage.table.nodeCount', {n: (row.nodes ?? []).length}) }}
            （{{ summarizeNodes(row.nodes) }}）
          </span>
          <span v-else>
            {{ t('profileManage.table.nodeCount', {n: (row.sentinels ?? []).length}) }}
            → {{ row.masterName }}
          </span>
        </template>
      </el-table-column>
      <el-table-column :label="t('profileManage.table.database')" width="90">
        <template #default="{ row }">{{ row.database ?? 0 }}</template>
      </el-table-column>
      <el-table-column :label="t('profileManage.table.auth')" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="row.credentialPresence === 'PRESENT' ? 'success' : 'info'">
            {{ t(presenceTextKey(row.credentialPresence)) }}
          </el-tag>
          <span v-if="row.username" class="cell-sub">{{ row.username }}</span>
        </template>
      </el-table-column>
      <!-- 哨兵密码只在哨兵模式有意义，其余模式不占列 -->
      <el-table-column
          v-if="profiles.some((p) => p.mode === 'SENTINEL')"
          :label="t('profileManage.table.sentinelAuth')"
          width="120"
      >
        <template #default="{ row }">
          <el-tag
              v-if="row.mode === 'SENTINEL'"
              size="small"
              :type="row.sentinelCredentialPresence === 'PRESENT' ? 'success' : 'info'"
          >
            {{ t(presenceTextKey(row.sentinelCredentialPresence)) }}
          </el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('profileManage.table.state')" width="100">
        <template #default="{ row }">
          <span class="state-cell">
            <span :style="{ background: dotColor(row) }" class="state-dot"/>
            {{ row.profileState === 'CONNECTED' ? t('common.connected') : t('common.offline') }}
          </span>
        </template>
      </el-table-column>
      <el-table-column :label="t('profileManage.table.actions')" width="190" fixed="right">
        <template #default="{ row }">
          <el-button size="small" text @click="openEdit(row)">{{ t('common.edit') }}</el-button>
          <el-button size="small" text type="danger" @click="onDelete(row)">
            {{ t('profileManage.table.delete') }}
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-slot">
          <p>{{ t('profileManage.title') }}</p>
          <el-button type="primary" @click="openCreate">{{ t('profileManage.add') }}</el-button>
        </div>
      </template>
    </el-table>

    <ProfileFormDialog
        v-model="dialogVisible"
        :profile="editingProfile"
        @saved="onSaved"
    />
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {useI18n} from 'vue-i18n'
import {ElMessage} from 'element-plus'
import {Plus} from '@element-plus/icons-vue'
import ProfileFormDialog from '../components/ProfileFormDialog.vue'
import {createProfile, deleteProfile, type ProfileUpsertPayload, type ProfileVO, updateProfile,} from '../api'
import {useCrudConfirm} from '../composables/useCrudConfirm'
import {ensureActiveProfile, forgetProfileDb, injectGlobalState,} from '../composables/useGlobalState'
import {presenceTextKey} from './profileForm'

const {t} = useI18n()
const route = useRoute()
const router = useRouter()
const {confirm} = useCrudConfirm()

const state = injectGlobalState()
const {profiles, profilesLoaded, loadProfiles, activeProfileId} = state

const loading = ref(false)
const dialogVisible = ref(false)
/** 非 null = 编辑模式；null = 新建 */
const editingProfile = ref<ProfileVO | null>(null)

const dotColor = (p: ProfileVO) =>
    p.profileState === 'CONNECTED' ? 'var(--el-color-success)' : 'var(--el-color-danger)'

const modeTagType = (mode: string) =>
    mode === 'CLUSTER' ? 'warning' : mode === 'SENTINEL' ? 'success' : 'info'

/** 节点列表摘要：只展示前两个 host，避免长列表撑爆单元格 */
const summarizeNodes = (nodes?: Array<{ host: string; port: number }> | null): string => {
  const list = nodes ?? []
  if (list.length === 0) return '-'
  const shown = list.slice(0, 2).map((n) => `${n.host}:${n.port}`).join(', ')
  return list.length > 2 ? `${shown} …` : shown
}

const openCreate = () => {
  editingProfile.value = null
  dialogVisible.value = true
}

const openEdit = (p: ProfileVO) => {
  editingProfile.value = p
  dialogVisible.value = true
}

/** 表单保存后的落地请求：新建 / 更新分流 */
const onSaved = async (payload: ProfileUpsertPayload) => {
  try {
    if (editingProfile.value?.id != null) {
      await updateProfile(editingProfile.value.id, payload)
      ElMessage.success(t('profileManage.messages.updated'))
    } else {
      const created = await createProfile(payload)
      ElMessage.success(t('profileManage.messages.created'))
      // 新建后自动激活，省去用户再去顶栏手动切换
      if (created.data?.id != null) state.setActiveProfile(created.data.id)
    }
    await loadProfiles()
    ensureActiveProfile(state)
  } catch (e: any) {
    ElMessage.error(e.message)
  }
}

const onDelete = async (p: ProfileVO) => {
  const ok = await confirm(
      t('profileManage.confirmDelete.body', {name: p.name}),
      t('profileManage.confirmDelete.title'),
      'error',
  )
  if (!ok) return
  try {
    await deleteProfile(p.id)
    ElMessage.success(t('profileManage.messages.deleted'))
    // 清掉该实例的 db 偏好，避免 id 复用后读到陈旧索引
    forgetProfileDb(p.id)
    await loadProfiles()
    ensureActiveProfile(state)
  } catch (e: any) {
    ElMessage.error(t('profileManage.deleteFailed', {msg: e.message}))
  }
}

// 首次启动引导会带 ?new=1 跳到这里并自动打开新建对话框。
// 必须用 watcher 而不是只在 onMounted 里判断：
// 首启时零连接重定向已把页面切到 /profiles（组件早已挂载），
// 引导框的「添加连接」只更新 query，组件不会重新挂载，onMounted 不会再执行。
watch(
    () => route.query.new,
    (v) => {
      if (v !== '1') return
      openCreate()
      // 清掉 query，避免刷新后再次自动弹出
      router.replace({path: '/profiles'})
    },
    {immediate: true},
)

onMounted(async () => {
  if (!profilesLoaded.value) {
    loading.value = true
    try {
      await loadProfiles()
    } catch (e: any) {
      ElMessage.error(t('profileManage.messages.loadFailed', {msg: e.message}))
    } finally {
      loading.value = false
    }
  }
})
</script>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.page-title {
  margin: 0;
  font-size: 16px;
}

.cell-sub {
  display: block;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}

.state-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.state-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.empty-slot {
  padding: 20px;
}
</style>