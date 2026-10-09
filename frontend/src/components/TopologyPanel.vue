<template>
  <div class="topology">
    <!--
      拓扑仅集群/哨兵有。单机模式下后端对该端点返回 40001，
      故由父级决定是否渲染本组件 —— 不发一个注定失败的请求。
    -->
    <el-descriptions v-if="topology" :column="4" border size="small" class="summary">
      <el-descriptions-item :label="t('monitor.topology.state')">
        {{ topology.state ?? '—' }}
      </el-descriptions-item>
      <el-descriptions-item :label="t('monitor.topology.slotsAssigned')">
        {{ topology.slotsAssigned ?? '—' }}
      </el-descriptions-item>
      <el-descriptions-item :label="t('monitor.topology.slotsOk')">
        {{ topology.slotsOk ?? '—' }}
      </el-descriptions-item>
      <el-descriptions-item :label="t('monitor.topology.nodes')">
        {{ nodes.length }}
      </el-descriptions-item>
    </el-descriptions>

    <el-table v-loading="loading" :data="nodes" border size="small" class="node-table">
      <el-table-column :label="t('monitor.topology.id')" prop="id" min-width="140"/>
      <el-table-column :label="t('monitor.topology.addr')" prop="addr" min-width="160"/>
      <el-table-column :label="t('monitor.topology.role')" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.role === 'master' ? 'warning' : 'info'">
            {{ row.role === 'master' ? t('monitor.topology.master') : t('monitor.topology.slave') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('monitor.topology.connected')" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.connected ? 'success' : 'danger'">
            {{ row.connected ? t('common.connected') : t('common.offline') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('monitor.topology.memory')" width="110">
        <template #default="{ row }">{{ row.memoryUsageHuman ?? '—' }}</template>
      </el-table-column>
      <el-table-column :label="t('monitor.topology.clients')" width="90">
        <template #default="{ row }">{{ formatNumber(row.clients) }}</template>
      </el-table-column>
      <el-table-column :label="t('monitor.topology.opsPerSec')" width="100">
        <template #default="{ row }">{{ formatNumber(row.opsPerSec) }}</template>
      </el-table-column>
      <el-table-column :label="t('monitor.topology.masterId')" prop="masterId" min-width="140"/>
      <template #empty>
        <div class="empty-cell">{{ t('monitor.topology.empty') }}</div>
      </template>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import {computed, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {ElMessage} from 'element-plus'
import {ApiError, type ClusterNodeVO, type ClusterTopologyVO, ErrorCode, getTopology,} from '../api'
import {formatNumber} from '../composables/useFormat'

const {t} = useI18n()

const props = defineProps<{
  profileId: number
}>()

/** 拓扑加载成功后把节点地址抛给父级，供 INFO 页的 node 下拉使用 */
const emit = defineEmits<{ (e: 'nodes', addrs: string[]): void }>()

const topology = ref<ClusterTopologyVO | null>(null)
const loading = ref(false)

const nodes = computed<ClusterNodeVO[]>(() => topology.value?.nodes ?? [])

const load = async () => {
  loading.value = true
  try {
    const res = await getTopology(props.profileId)
    topology.value = res.data ?? null
    emit('nodes', nodes.value.map((n) => n.addr).filter(Boolean))
  } catch (e: any) {
    topology.value = null
    emit('nodes', [])
    // 40001 = 单机模式无拓扑，属预期而非故障，不弹错误 toast
    if (e instanceof ApiError && e.code === ErrorCode.VALIDATION_ERROR) {
      ElMessage.info(t('monitor.topology.unsupported'))
      return
    }
    ElMessage.error(t('monitor.loadFailed', {msg: e.message}))
  } finally {
    loading.value = false
  }
}

watch(() => props.profileId, load, {immediate: true})
</script>

<style scoped>
.summary {
  margin-bottom: 12px;
}

.node-table {
  min-width: 0;
}

.empty-cell {
  padding: 16px;
  text-align: center;
  color: var(--el-text-color-placeholder);
}
</style>