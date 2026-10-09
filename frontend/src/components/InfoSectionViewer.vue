<template>
  <div class="info-viewer">
    <div class="toolbar">
      <el-select
          v-model="section"
          class="section-select"
          size="small"
          filterable
          :allow-create="true"
          default-first-option
          :placeholder="t('monitor.info.sectionPlaceholder')"
      >
        <!--
          常用分节快捷值，但 **allow-create 允许自定义**：后端不校验 section，
          直接透传给 Redis INFO。写死白名单会让新版本 Redis 的新分节无法查看。
        -->
        <el-option v-for="s in COMMON_SECTIONS" :key="s" :label="s" :value="s"/>
      </el-select>

      <!-- 集群下可选节点（host:port），从拓扑数据填充 -->
      <el-select
          v-if="nodes.length > 0"
          v-model="node"
          class="node-select"
          size="small"
          clearable
          :placeholder="t('monitor.info.nodePlaceholder')"
      >
        <el-option v-for="n in nodes" :key="n" :label="n" :value="n"/>
      </el-select>

      <el-button size="small" :loading="loading" :icon="Refresh" @click="load">
        {{ t('monitor.info.refresh') }}
      </el-button>
    </div>

    <el-table :data="rows" border size="small" max-height="420">
      <el-table-column :label="t('monitor.info.field')" prop="key" min-width="200"/>
      <el-table-column :label="t('monitor.info.value')" prop="value" min-width="260">
        <template #default="{ row }">
          <span class="mono" :title="row.value">{{ row.value }}</span>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-cell">{{ t('monitor.info.empty') }}</div>
      </template>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import {computed, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {ElMessage} from 'element-plus'
import {Refresh} from '@element-plus/icons-vue'
import {getServerInfo, type InfoVO} from '../api'

const {t} = useI18n()

/** 常用分节（快捷项，不构成白名单） */
const COMMON_SECTIONS = [
  'default',
  'server',
  'clients',
  'memory',
  'persistence',
  'stats',
  'replication',
  'cpu',
  'keyspace',
]

const props = defineProps<{
  profileId: number
  /** 集群/哨兵节点地址（host:port），空数组 = 单机或未加载拓扑 */
  nodes: string[]
}>()

const section = ref('default')
const node = ref<string>('')
const loading = ref(false)

const infoData = ref<InfoVO | null>(null)

/** V1 INFO 分节：响应是"分节名 → 键值对"的嵌套结构，这里摊平成两列 */
const rows = computed(() => {
  const info = infoData.value
  if (!info) return []
  const out: Array<{ key: string; value: string }> = []
  for (const [sectionName, fields] of Object.entries(info)) {
    if (!fields) continue
    // 只渲染当前请求的分节（后端按 section 返回，其余小节为 null）
    if (section.value !== 'default' && sectionName !== section.value) continue
    for (const [k, v] of Object.entries(fields)) {
      out.push({key: `${sectionName}.${k}`, value: v ?? ''})
    }
  }
  return out
})

const load = async () => {
  loading.value = true
  try {
    const res = await getServerInfo(props.profileId, section.value || 'default', node.value || undefined)
    infoData.value = res.data ?? null
  } catch (e: any) {
    infoData.value = null
    ElMessage.error(t('monitor.loadFailed', {msg: e.message}))
  } finally {
    loading.value = false
  }
}

watch(
    () => [props.profileId, props.nodes],
    () => {
      // 切换实例后清掉已失效的节点选择
      if (props.nodes.length === 0) node.value = ''
      load()
    },
    {immediate: true},
)
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.section-select {
  width: 200px;
}

.node-select {
  width: 180px;
}

.mono {
  font-family: monospace;
  font-size: 12px;
  word-break: break-all;
}

.empty-cell {
  padding: 16px;
  text-align: center;
  color: var(--el-text-color-placeholder);
}
</style>