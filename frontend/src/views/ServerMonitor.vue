<template>
  <div class="page">
    <div class="page-head">
      <h3 class="page-title">{{ t('monitor.title') }}</h3>
      <el-button :icon="Refresh" @click="refreshActive">
        {{ t('common.refresh') }}
      </el-button>
    </div>

    <el-empty v-if="profiles.length === 0" :description="t('monitor.empty.noProfile')">
      <el-button type="primary" @click="router.push('/profiles')">
        {{ t('monitor.empty.goManage') }}
      </el-button>
    </el-empty>
    <el-alert
        v-else-if="!profileId"
        :title="t('monitor.empty.noProfileSelected')"
        type="info"
        :closable="false"
        show-icon
    />

    <!--
      ⚠️ el-tabs 的 pane 必须**显式写 name**，否则页签全灰（EP 2.14 已知坑）。
    -->
    <el-tabs v-else v-model="activeTab" class="tabs">
      <el-tab-pane :label="t('monitor.tabs.overview')" name="overview">
        <div class="overview-grid">
          <el-card
              v-for="card in overviewCards"
              :key="card.label"
              class="overview-card"
              shadow="never"
          >
            <div class="card-label">{{ card.label }}</div>
            <div class="card-value">{{ card.value }}</div>
          </el-card>
        </div>
        <div class="dbsize-row">
          <span class="dbsize-label">{{ t('monitor.overview.dbSize') }}</span>
          <span class="dbsize-value">{{ formatNumber(dbsize) }}</span>
        </div>
      </el-tab-pane>

      <el-tab-pane :label="t('monitor.tabs.info')" name="info">
        <InfoSectionViewer :key="`info-${remountKey}`" :profile-id="profileId" :nodes="nodeAddrs"/>
      </el-tab-pane>

      <el-tab-pane :label="t('monitor.tabs.clients')" name="clients">
        <ClientTable :key="`clients-${remountKey}`" :profile-id="profileId"/>
      </el-tab-pane>

      <el-tab-pane :label="t('monitor.tabs.slowlog')" name="slowlog">
        <SlowLogTable :key="`slowlog-${remountKey}`" :profile-id="profileId"/>
      </el-tab-pane>

      <!-- 拓扑 tab 仅集群/哨兵显示：单机后端返 40001，不发无谓请求 -->
      <el-tab-pane
          v-if="showTopology"
          :label="t('monitor.tabs.topology')"
          name="topology"
      >
        <TopologyPanel :key="`topology-${remountKey}`" :profile-id="profileId" @nodes="onNodesLoaded"/>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import {computed, onMounted, ref, watch} from 'vue'
import {useRouter} from 'vue-router'
import {useI18n} from 'vue-i18n'
import {ElMessage} from 'element-plus'
import {Refresh} from '@element-plus/icons-vue'
import ClientTable from '../components/ClientTable.vue'
import InfoSectionViewer from '../components/InfoSectionViewer.vue'
import SlowLogTable from '../components/SlowLogTable.vue'
import TopologyPanel from '../components/TopologyPanel.vue'
import {getDbSize, getServerOverview, type ServerOverviewVO} from '../api'
import {injectGlobalState} from '../composables/useGlobalState'
import {formatNumber, formatPercent} from '../composables/useFormat'

const {t} = useI18n()
const router = useRouter()

const {profiles, activeProfileId, activeDb, activeProfile} = injectGlobalState()

const profileId = computed(() => activeProfileId.value ?? 0)

/** 单机模式无拓扑 */
const showTopology = computed(() => activeProfile.value?.mode !== 'STANDALONE')

const activeTab = ref('overview')
/** 拓扑节点地址，供 INFO 页 node 下拉填充 */
const nodeAddrs = ref<string[]>([])
/** 手动刷新计数：作为各 tab 子组件的 key，递增即强制重建并重新拉数据 */
const remountKey = ref(0)

const overview = ref<ServerOverviewVO | null>(null)
/** DBSIZE：集群为各 master 之和 */
const dbsize = ref<number | null>(null)

const overviewCards = computed(() => {
  const o = overview.value
  return [
    {label: t('monitor.overview.usedMemory'), value: o?.usedMemory ?? '—'},
    {label: t('monitor.overview.peakMemory'), value: o?.peakMemory ?? '—'},
    {label: t('monitor.overview.connectedClients'), value: formatNumber(o?.connectedClients)},
    {label: t('monitor.overview.totalCommands'), value: formatNumber(o?.totalCommands)},
    {label: t('monitor.overview.opsPerSec'), value: formatNumber(o?.opsPerSec)},
    {label: t('monitor.overview.keyspaceHits'), value: formatNumber(o?.keyspaceHits)},
    {label: t('monitor.overview.keyspaceMisses'), value: formatNumber(o?.keyspaceMisses)},
    {label: t('monitor.overview.hitRate'), value: formatPercent(o?.hitRate)},
    {label: t('monitor.overview.uptimeDays'), value: o?.uptimeDays == null ? '—' : String(o.uptimeDays)},
    {label: t('monitor.overview.redisVersion'), value: o?.redisVersion ?? '—'},
  ]
})

const loadOverview = async () => {
  // V5 后端忽略 db 参数，前端不传
  const res = await getServerOverview(profileId.value)
  overview.value = res.data ?? null
}

const loadDbSize = async () => {
  const res = await getDbSize(profileId.value, activeDb.value)
  dbsize.value = res.data?.dbsize ?? null
}

const loadAll = async () => {
  try {
    await Promise.all([loadOverview(), loadDbSize()])
  } catch (e: any) {
    ElMessage.error(t('monitor.loadFailed', {msg: e.message}))
  }
}

const onNodesLoaded = (addrs: string[]) => {
  nodeAddrs.value = addrs
}

const refreshActive = () => {
  // Overview tab 的数据（overview/dbsize）由本页持有，直接重拉；
  // 其余四个 tab 的数据在各子组件内，用 remountKey 强制重建并触发其 immediate 拉取。
  if (activeTab.value === 'overview') {
    loadAll()
  }
  remountKey.value++
}

watch(
    () => [profileId.value, activeDb.value],
    () => {
      // 切换实例后拓扑节点地址失效
      nodeAddrs.value = []
      if (profileId.value > 0) loadAll()
    },
    {immediate: true},
)

onMounted(() => {
  if (profileId.value > 0) loadAll()
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

.overview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
}

.overview-card {
  text-align: left;
}

.card-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}

.card-value {
  font-size: 20px;
  font-weight: 600;
  word-break: break-all;
}

.dbsize-row {
  margin-top: 12px;
  display: flex;
  gap: 8px;
  align-items: baseline;
}

.dbsize-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.dbsize-value {
  font-size: 18px;
  font-weight: 600;
}
</style>