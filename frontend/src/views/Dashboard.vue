<template>
  <div class="page">
    <div class="page-head">
      <h3 class="page-title">{{ t('dashboard.title') }}</h3>
      <el-button :icon="Refresh" :loading="loading" @click="loadAll">
        {{ t('dashboard.refresh') }}
      </el-button>
    </div>

    <el-empty v-if="profiles.length === 0" :description="t('dashboard.instance.noProfile')">
      <el-button type="primary" @click="router.push('/profiles')">
        {{ t('dashboard.instance.goManage') }}
      </el-button>
    </el-empty>

    <template v-else>
      <!--
        ⚠️ 命中率无聚合口径：DashboardOverviewVO 只给 clients/opsPerSec/totalKeys，
        不给 hitRate（那是 InstanceMetricsVO 的字段）。
        跨实例加权平均需要各实例 hits/misses，后端未提供 —— 故不虚构，改用已给字段。
      -->
      <el-row :gutter="12" class="stat-row">
        <el-col :xs="12" :sm="8" :md="4">
          <StatCard :label="t('dashboard.stat.totalProfiles')" :value="overview?.totalProfiles ?? 0"/>
        </el-col>
        <el-col :xs="12" :sm="8" :md="4">
          <StatCard
              :label="t('dashboard.stat.onlineProfiles')"
              :value="overview?.onlineProfiles ?? 0"
              type="success"
          />
        </el-col>
        <el-col :xs="12" :sm="8" :md="4">
          <StatCard
              :label="t('dashboard.stat.offlineProfiles')"
              :value="overview?.offlineProfiles ?? 0"
              :type="(overview?.offlineProfiles ?? 0) > 0 ? 'warning' : 'info'"
          />
        </el-col>
        <el-col :xs="12" :sm="8" :md="4">
          <StatCard
              :label="t('dashboard.stat.memoryUsage')"
              :value="overview?.memoryUsageHuman ?? '-'"
              :hint="peakHint"
          />
        </el-col>
        <el-col :xs="12" :sm="8" :md="4">
          <StatCard :label="t('dashboard.stat.totalKeys')" :value="overview?.totalKeys ?? 0"/>
        </el-col>
        <el-col :xs="12" :sm="8" :md="4">
          <StatCard :label="t('dashboard.stat.opsPerSec')" :value="overview?.opsPerSec ?? 0"/>
        </el-col>
      </el-row>

      <!-- 趋势：10s 轮询，与后端 5s 采样间隔匹配 -->
      <el-row :gutter="12" class="trend-row">
        <el-col :xs="24" :lg="12">
          <el-card shadow="never">
            <TrendChart
                v-model:window-seconds="trendWindow"
                :title="t('dashboard.trend.memoryTitle')"
                :timestamps="memoryTimestamps"
                :series="memorySeries"
                bytes
            />
          </el-card>
        </el-col>
        <el-col :xs="24" :lg="12">
          <el-card shadow="never">
            <TrendChart
                v-model:window-seconds="trendWindow"
                :title="t('dashboard.trend.loadTitle')"
                :timestamps="loadTimestamps"
                :series="loadSeries"
            />
          </el-card>
        </el-col>
      </el-row>

      <!-- 每实例卡片 -->
      <h4 class="section-title">{{ t('dashboard.instance.title') }}</h4>
      <el-empty v-if="instances.length === 0" :description="t('common.noData')"/>
      <el-row v-else :gutter="12">
        <el-col
            v-for="inst in instances"
            :key="inst.profileId"
            :xs="24"
            :sm="12"
            :lg="8"
            class="instance-col"
        >
          <el-card
              :class="{ 'instance-card--offline': inst.profileState !== 'CONNECTED' }"
              class="instance-card"
              shadow="never"
          >
            <template #header>
              <div class="instance-head">
                <span :style="{ background: dotColor(inst) }" class="state-dot"/>
                <span class="instance-name">{{ inst.name }}</span>
                <el-tag size="small" :type="modeTagType(inst.mode)">
                  {{ t(`profileManage.mode.${inst.mode}`) }}
                </el-tag>
              </div>
            </template>

            <el-descriptions :column="2" size="small">
              <el-descriptions-item :label="t('dashboard.instance.nodes')">
                {{ inst.nodeCount ?? '—' }}
              </el-descriptions-item>
              <el-descriptions-item :label="t('dashboard.instance.clusterState')">
                {{ inst.clusterState ?? '—' }}
              </el-descriptions-item>
              <el-descriptions-item :label="t('dashboard.instance.keys')">
                {{ formatNumber(inst.totalKeys) }}
              </el-descriptions-item>
              <el-descriptions-item :label="t('dashboard.instance.clients')">
                {{ formatNumber(inst.clients) }}
              </el-descriptions-item>
              <el-descriptions-item :label="t('dashboard.instance.opsPerSec')">
                {{ formatNumber(inst.opsPerSec) }}
              </el-descriptions-item>
              <el-descriptions-item :label="t('dashboard.instance.hitRate')">
                {{ formatPercent(inst.hitRate) }}
              </el-descriptions-item>
              <el-descriptions-item :label="t('dashboard.instance.latency')">
                {{ inst.latencyMs == null ? '—' : `${inst.latencyMs} ms` }}
              </el-descriptions-item>
              <el-descriptions-item :label="t('dashboard.instance.cpu')">
                <!--
                  cpuUsagePercent 是后端启动约 10s 后的**差分值**：
                  null = 尚无采样（显示破折号），0.0 = 真实的零（离线实例）。
                  两者语义不同，不可统一显示 0。
                -->
                <el-tooltip
                    v-if="inst.cpuUsagePercent == null"
                    :content="t('dashboard.instance.cpuPending')"
                >
                  <span class="muted">—</span>
                </el-tooltip>
                <span v-else>{{ formatPercent(inst.cpuUsagePercent) }}</span>
              </el-descriptions-item>
            </el-descriptions>

            <div class="memory-row">
              <span class="memory-label">{{ t('dashboard.instance.memory') }}</span>
              <span class="memory-value">
                {{ inst.memoryUsageHuman ?? '—' }}
                <span v-if="inst.maxMemoryBytes" class="memory-max">
                  / {{ formatBytes(inst.maxMemoryBytes) }}
                </span>
              </span>
            </div>
            <!-- maxmemory=0（不设上限）时后端恒回 0.0，显示进度条会被误读为"内存全空闲" -->
            <div
                v-if="!inst.maxMemoryBytes && inst.profileState === 'CONNECTED'"
                class="no-max-memory"
            >
              {{ t('dashboard.instance.noMaxMemory') }}
            </div>
            <el-progress
                v-else
                :percentage="progressPercent(inst.memoryUsagePercent)"
                :format="memoryPercentText"
                :stroke-width="10"
                :status="inst.profileState === 'CONNECTED' ? undefined : 'exception'"
            />

            <div v-if="inst.profileState !== 'CONNECTED'" class="offline-hint">
              {{ t('dashboard.instance.offlineHint') }}
            </div>
          </el-card>
        </el-col>
      </el-row>
    </template>
  </div>
</template>

<script setup lang="ts">
import {computed, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {useRouter} from 'vue-router'
import {useI18n} from 'vue-i18n'
import {ElMessage} from 'element-plus'
import {Refresh} from '@element-plus/icons-vue'
import StatCard from '../components/StatCard.vue'
import TrendChart from '../components/TrendChart.vue'
import type {TrendSeries} from '../components/trendTypes'
import {
  type DashboardOverviewVO,
  getDashboardOverview,
  getLoadTrend,
  getMemoryTrend,
  type InstanceMetricsVO,
  type LoadTrendVO,
  type MemoryTrendVO,
} from '../api'
import {injectGlobalState} from '../composables/useGlobalState'
import {formatBytes, formatNumber, formatPercent} from '../composables/useFormat'

const {t} = useI18n()
const router = useRouter()

const {profiles, loadProfiles, profilesLoaded} = injectGlobalState()

const overview = ref<DashboardOverviewVO | null>(null)
const memoryTrend = ref<MemoryTrendVO | null>(null)
const loadTrend = ref<LoadTrendVO | null>(null)
const loading = ref(false)

/** 趋势采样窗口（秒）；后端默认 300 */
const trendWindow = ref(300)

const instances = computed(() => overview.value?.instances ?? [])

const peakHint = computed(() => {
  const v = overview.value?.peakMemoryBytes
  return v ? `${t('dashboard.instance.peakMemory')}: ${formatBytes(v)}` : undefined
})

const dotColor = (inst: InstanceMetricsVO) =>
    inst.profileState === 'CONNECTED' ? 'var(--el-color-success)' : 'var(--el-color-danger)'

const modeTagType = (mode: string) =>
    mode === 'CLUSTER' ? 'warning' : mode === 'SENTINEL' ? 'success' : 'info'

/** 进度条百分比：null（后端未给）→ 0，避免 NaN 传给 el-progress */
const progressPercent = (v?: number | null) =>
    v == null || Number.isNaN(v) ? 0 : Math.min(100, Math.max(0, Number(v.toFixed(2))))

/** 内存占用百分比文本：恒定两位小数（如 12.50%），el-progress 默认文本不补零 */
const memoryPercentText = (p: number) => `${p.toFixed(2)}%`

// ---- 趋势数据整形：后端是 {samples:[{timestamp, points:[{profileId,name,...}]}]}，
//      折线图需要"每个实例一条线"，这里转置成 series 结构 ----

const memoryTimestamps = computed(() =>
    (memoryTrend.value?.samples ?? []).map((s) => s.timestamp),
)

const memorySeries = computed<TrendSeries[]>(() => {
  const samples = memoryTrend.value?.samples ?? []
  if (samples.length === 0) return []
  // 从首个采样点推导实例列表与展示名
  const first = samples[0].points ?? []
  return first.map((p) => ({
    profileId: p.profileId,
    name: p.name ?? `#${p.profileId}`,
    values: samples.map((s) => {
      const point = (s.points ?? []).find((x) => x.profileId === p.profileId)
      return Number(point?.usedMemoryBytes ?? 0)
    }),
  }))
})

const loadTimestamps = computed(() => (loadTrend.value?.samples ?? []).map((s) => s.timestamp))

const loadSeries = computed<TrendSeries[]>(() => {
  const samples = loadTrend.value?.samples ?? []
  if (samples.length === 0) return []
  const first = samples[0].points ?? []
  return first.map((p) => ({
    profileId: p.profileId,
    name: p.name ?? `#${p.profileId}`,
    values: samples.map((s) => {
      const point = (s.points ?? []).find((x) => x.profileId === p.profileId)
      return Number(point?.opsPerSec ?? 0)
    }),
  }))
})

const loadOverview = async () => {
  const res = await getDashboardOverview()
  overview.value = res.data ?? null
}

const loadTrends = async () => {
  const [mem, load] = await Promise.all([
    getMemoryTrend(trendWindow.value),
    getLoadTrend(trendWindow.value),
  ])
  memoryTrend.value = mem.data ?? null
  loadTrend.value = load.data ?? null
}

const loadAll = async () => {
  loading.value = true
  try {
    await Promise.all([loadOverview(), loadTrends()])
  } catch (e: any) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

// 切换采样窗口立即重拉趋势 —— 不用等最长 10s 的轮询周期才生效
watch(trendWindow, () => {
  loadTrends().catch(() => {
  })
})

// ---- 10s 轮询：与后端 dashboard.sample-interval-ms（5s）匹配 ----
let timer: number | null = null

const tick = () => {
  if (document.hidden || profiles.value.length === 0) return
  loadAll().catch(() => {
    // 轮询失败静默：保留上一帧数据，下一轮再试
  })
}

onMounted(async () => {
  if (!profilesLoaded.value) {
    await loadProfiles().catch(() => {
    })
  }
  await loadAll()
  timer = window.setInterval(tick, 10000)
})

onBeforeUnmount(() => {
  if (timer !== null) {
    clearInterval(timer)
    timer = null
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

.stat-row {
  margin-bottom: 4px;
}

.trend-row {
  margin-bottom: 12px;
}

.section-title {
  margin: 16px 0 10px;
  font-size: 14px;
}

.instance-col {
  margin-bottom: 12px;
}

.instance-card {
  height: 100%;
}

/* 离线实例全字段归零，灰化以免被当成真实数据读 */
.instance-card--offline {
  opacity: 0.65;
}

.instance-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.instance-name {
  font-weight: 500;
  flex: 1;
}

.state-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.memory-row {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin: 8px 0 4px;
}

.memory-max {
  color: var(--el-text-color-placeholder);
}

/* 未设置 maxmemory 时替代进度条的占位提示 */
.no-max-memory {
  padding: 4px 0 2px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.muted {
  color: var(--el-text-color-placeholder);
}

.offline-hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-warning);
}
</style>