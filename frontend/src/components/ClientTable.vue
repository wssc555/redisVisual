<template>
  <div class="client-table">
    <el-table v-loading="loading" :data="clients" border size="small" max-height="480">
      <el-table-column :label="t('monitor.clients.id')" prop="id" width="120"/>
      <el-table-column :label="t('monitor.clients.addr')" prop="addr" min-width="180"/>
      <el-table-column :label="t('monitor.clients.db')" prop="db" width="80"/>
      <el-table-column :label="t('monitor.clients.age')" prop="age" width="120"/>
      <el-table-column :label="t('monitor.clients.idle')" prop="idle" width="120"/>
      <el-table-column :label="t('monitor.clients.cmd')" prop="cmd" min-width="260">
        <template #default="{ row }">
          <span class="mono" :title="row.cmd">{{ row.cmd }}</span>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-cell">{{ t('monitor.clients.empty') }}</div>
      </template>
    </el-table>
    <div v-if="clients.length > 0" class="total-hint">
      {{ t('monitor.clients.total', {n: clients.length}) }}
    </div>
  </div>
</template>

<script setup lang="ts">
import {ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {ElMessage} from 'element-plus'
import {type ClientInfoVO, getClients} from '../api'

const {t} = useI18n()

const props = defineProps<{ profileId: number }>()

const clients = ref<ClientInfoVO[]>([])
const loading = ref(false)

const load = async () => {
  loading.value = true
  try {
    const res = await getClients(props.profileId)
    clients.value = res.data ?? []
  } catch (e: any) {
    clients.value = []
    ElMessage.error(t('monitor.loadFailed', {msg: e.message}))
  } finally {
    loading.value = false
  }
}

watch(() => props.profileId, load, {immediate: true})
</script>

<style scoped>
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

.total-hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>